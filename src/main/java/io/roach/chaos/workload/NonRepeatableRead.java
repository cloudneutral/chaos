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
import io.roach.chaos.util.Exporter;
import io.roach.chaos.util.TransactionWrapper;

@Note("P2 non-repeatable read anomaly")
public class NonRepeatableRead extends AbstractWorkload {
    private Collection<Account> accounts = List.of();

    private final int repeatedReads = 10;

    private final Map<Account.Id, Set<BigDecimal>> anomalies = Collections.synchronizedMap(new HashMap<>());

    private final AtomicInteger reads = new AtomicInteger();

    private final AtomicInteger writes = new AtomicInteger();

    @Override
    protected void preValidate() {
    }

    @Override
    public List<Duration> doExecute() {
        // Let's roll with 10% writes
        if (ThreadLocalRandom.current().nextDouble(1.00) < settings.getReadWriteRatio()) {
            reads.incrementAndGet();
            return readRows();
        }
        writes.incrementAndGet();
        return writeRows();
    }

    public List<Duration> readRows() {
        final List<Duration> durations = new ArrayList<>();

        final Map<Account.Id, List<BigDecimal>> balanceObservations = new LinkedHashMap<>();

        // Within the same transaction, all reads must return the same value otherwise its a P2 anomaly
        TransactionCallback<Void> callback = status -> {
            // Clear previous observation on retries
            balanceObservations.clear();

            accounts.forEach(a -> {
                // Add write mutex scoped by account id
                IntStream.rangeClosed(1, repeatedReads)
                        .forEach(value -> {
                            Account account = accountRepository.findById(a.getId(), settings.getLockType());

                            balanceObservations.computeIfAbsent(a.getId(),
                                            x -> new ArrayList<>())
                                    .add(account.getBalance());
                        });
            });
            return null;
        };

        TransactionWrapper transactionWrapper = transactionWrapper();
        transactionWrapper.execute(callback, durations::addAll);

        // Sum up for reporting
        balanceObservations.forEach((id, balances) -> {
            List<BigDecimal> distinctValues = balances.stream().distinct().toList();
            if (distinctValues.size() != 1) {
                anomalies.computeIfAbsent(id, x -> new TreeSet<>())
                        .addAll(distinctValues);
            }
        });

        return durations;
    }

    public List<Duration> writeRows() {
        final List<Duration> durations = new ArrayList<>();

        TransactionCallback<Void> callback = status -> {
            accounts.forEach(a -> {
                if (settings.isOptimisticLocking()) {
                    accountRepository.updateBalanceCAS(a.addBalance(BigDecimal.ONE));
                } else {
                    accountRepository.updateBalance(a.addBalance(BigDecimal.ONE));
                }
            });
            return null;
        };

        TransactionWrapper transactionWrapper = transactionWrapper();
        transactionWrapper.execute(callback, durations::addAll);

        return durations;
    }

    @Override
    protected void beforeExecution() {
        this.accounts = accountRepository.findTargetAccounts(settings.getSelection(), settings.isRandomSelection());
    }

    @Override
    protected void afterExecution(Exporter exporter) {
        ConsoleOutput.header("Consistency Check");


        anomalies.forEach((id, balances) -> {
            ConsoleOutput.error("Observed non-repeatable values for key %s: %s".formatted(id, balances));
        });

        ConsoleOutput.printLeft("Total reads", "%d".formatted(reads.get()));
        ConsoleOutput.printLeft("Total writes", "%d".formatted(writes.get()));

        if (anomalies.isEmpty()) {
            ConsoleOutput.info("You are good! %s".formatted(AsciiArt.happy()));
            ConsoleOutput.info("To observe anomalies, try read-committed without locking (ex: --isolation rc)");
        } else {
            ConsoleOutput.error("Observed %d accounts with non-repeatable reads! %s"
                    .formatted(anomalies.size(), AsciiArt.flipTableRoughly()));
            ConsoleOutput.info("To avoid anomalies, try read-committed with locking or repeatable-read or higher isolation (ex: --locking for_share)");
        }
    }
}

