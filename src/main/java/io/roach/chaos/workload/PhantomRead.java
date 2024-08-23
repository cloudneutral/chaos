package io.roach.chaos.workload;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.springframework.transaction.support.TransactionCallback;

import io.roach.chaos.model.Account;
import io.roach.chaos.util.AsciiArt;
import io.roach.chaos.util.ConsoleOutput;
import io.roach.chaos.util.RandomData;
import io.roach.chaos.util.TransactionWrapper;

@Note("P3 phantom read anomaly")
public class PhantomRead extends AbstractWorkload {
    private Collection<Account> accounts = List.of();

    private final int repeatedReads = 10;

    private final Map<Long, Set<Integer>> anomalies = Collections.synchronizedMap(new HashMap<>());

    private final AtomicInteger selects = new AtomicInteger();

    private final AtomicInteger inserts = new AtomicInteger();

    private final AtomicInteger deletes = new AtomicInteger();

    @Override
    protected void doBeforeExecutions() {
        this.accounts = accountRepository.findTargetAccounts(settings.getSelection(), settings.isRandomSelection());
    }

    @Override
    public List<Duration> oneExecution() {
        // Let's roll with 10% writes
        if (ThreadLocalRandom.current().nextDouble(1.00) < settings.getReadWriteRatio()) {
            selects.incrementAndGet();
            return selectRows();
        }

        if (ThreadLocalRandom.current().nextDouble(1.00) < .50) {
            inserts.incrementAndGet();
            return createRows();
        }

        deletes.incrementAndGet();
        return deleteRows();
    }

    private List<Duration> selectRows() {
        final Map<Long, List<Integer>> observations = new LinkedHashMap<>();

        // Within the same transaction, all reads must return the same value otherwise its a P2 anomaly
        TransactionCallback<Void> callback = status -> {
            // Clear previous observations on retries
            observations.clear();

            accounts.forEach(a -> {
                IntStream.rangeClosed(1, repeatedReads)
                        .forEach(value -> {
                            List<Account> accounts =
                                    accountRepository.findAccountsById(a.getId().getId(), settings.getLockType());

                            observations.computeIfAbsent(a.getId().getId(), x -> new ArrayList<>())
                                    .add(accounts.size()); // Must match predicate
                        });
            });
            return null;
        };

        final List<Duration> durations = new ArrayList<>();

        TransactionWrapper transactionWrapper = transactionWrapper();
        transactionWrapper.execute(callback, durations::addAll);

        // Sum up for reporting
        observations.forEach((id, balances) -> {
            List<Integer> distinctValues = balances.stream().distinct().toList();
            if (distinctValues.size() != 1) {
                anomalies.computeIfAbsent(id, x -> new TreeSet<>())
                        .addAll(distinctValues);
            }
        });

        return durations;
    }

    private List<Duration> createRows() {
        TransactionCallback<Void> callback = status -> {
            accounts.forEach(a -> {
                Account extra = new Account();
                extra.setId(new Account.Id(a.getId().getId(), RandomData.randomString(32)));
                extra.setBalance(BigDecimal.TEN);
                extra.setName("New Type");

                accountRepository.createAccount(extra);
            });
            return null;
        };

        final List<Duration> durations = new ArrayList<>();
        TransactionWrapper transactionWrapper = transactionWrapper();
        transactionWrapper.execute(callback, durations::addAll);

        return durations;
    }

    private List<Duration> deleteRows() {
        TransactionCallback<Void> callback = status -> {
            accounts.forEach(a -> {
                accountRepository.deleteAccount(a.getId());
            });
            return null;
        };

        final List<Duration> durations = new ArrayList<>();
        TransactionWrapper transactionWrapper = transactionWrapper();
        transactionWrapper.execute(callback, durations::addAll);

        return durations;
    }

    @Override
    public void afterAllExecutions() {
        ConsoleOutput.header("Consistency Check");

        anomalies.forEach((id, balances) -> {
            ConsoleOutput.error("Observed phantom values for key %s: %s".formatted(id, balances));
        });

        ConsoleOutput.printLeft("Total selects", "%d".formatted(selects.get()));
        ConsoleOutput.printLeft("Total inserts", "%d".formatted(inserts.get()));
        ConsoleOutput.printLeft("Total deletes", "%d".formatted(deletes.get()));

        if (anomalies.isEmpty()) {
            ConsoleOutput.info("You are good! %s".formatted(AsciiArt.happy()));
            ConsoleOutput.info("To observe anomalies, try read-committed without locking (ex: --isolation rc)");
        } else {
            ConsoleOutput.error("Observed %d accounts returning phantom reads! %s"
                    .formatted(anomalies.size(), AsciiArt.flipTableRoughly()));
            ConsoleOutput.info(
                    "To avoid anomalies, try repeatable-read or higher isolation (ex: --isolation rr)");
        }
    }
}

