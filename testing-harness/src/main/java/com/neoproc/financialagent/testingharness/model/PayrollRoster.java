package com.neoproc.financialagent.testingharness.model;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Mutable in-memory roster backing the {@code /employees} page. Seeded
 * from {@link EmployeeRegistry}; mutated by POST {@code /employees/submit}
 * so the page visibly reflects agent submissions across runs. Reset via
 * POST {@code /employees/reset} to re-seed between demo runs without
 * restarting the harness.
 */
@Service
public class PayrollRoster {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Employee> byId = new LinkedHashMap<>();

    @PostConstruct
    void seed() {
        reset();
    }

    public List<Employee> list() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(byId.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Replace an existing employee's salary. No-op if the id is unknown. */
    public boolean updateSalary(String id, BigDecimal newSalary) {
        lock.writeLock().lock();
        try {
            Employee current = byId.get(id);
            if (current == null) return false;
            if (current.currentSalary().compareTo(newSalary) == 0) return false;
            byId.put(id, new Employee(id, current.name(), newSalary));
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void reset() {
        lock.writeLock().lock();
        try {
            byId.clear();
            for (Employee e : EmployeeRegistry.EMPLOYEES) {
                byId.put(e.id(), e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Replace the entire roster with {@code seed}. Used by
     * {@code POST /employees/seed} to load a roster captured from an
     * upstream source-of-truth (e.g. AutoPlanilla) into the mock view —
     * lets demos show "the real payroll appearing on the INS-style page"
     * without needing to hardcode employee data in the harness.
     */
    public void replaceAll(List<Employee> seed) {
        lock.writeLock().lock();
        try {
            byId.clear();
            for (Employee e : seed) {
                byId.put(e.id(), e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
