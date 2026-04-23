package com.neoproc.financialagent.testingharness.controller;

import com.neoproc.financialagent.testingharness.model.Employee;
import com.neoproc.financialagent.testingharness.model.PayrollRoster;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only endpoint that replaces the harness roster with an arbitrary
 * employee list. Accepts the {@code employees[]} array from a
 * {@code payroll-capture-result.v1} envelope body so a demo pipeline can
 * "load captured payroll data into the mock INS view" with a single
 * curl. Accepts both {@code grossSalary} (capture-envelope shape) and
 * {@code currentSalary} field names for flexibility.
 */
@RestController
public class PayrollSeedController {

    private final PayrollRoster roster;

    public PayrollSeedController(PayrollRoster roster) {
        this.roster = roster;
    }

    @PostMapping("/employees/seed")
    public ResponseEntity<SeedResponse> seed(@RequestBody List<SeedEntry> entries) {
        List<Employee> employees = new ArrayList<>(entries.size());
        for (SeedEntry entry : entries) {
            BigDecimal salary = entry.grossSalary != null
                    ? entry.grossSalary
                    : entry.currentSalary != null ? entry.currentSalary : BigDecimal.ZERO;
            String displayName = entry.displayName != null ? entry.displayName : entry.name;
            employees.add(new Employee(entry.id, displayName, salary));
        }
        roster.replaceAll(employees);
        return ResponseEntity.ok(new SeedResponse(employees.size()));
    }

    /**
     * Deliberately permissive shape: accepts the capture-envelope names
     * ({@code id} / {@code displayName} / {@code grossSalary}) AND the
     * harness's own ({@code name} / {@code currentSalary}), so demo
     * scripts can pass capture-envelope rows through verbatim.
     */
    public static class SeedEntry {
        public String id;
        public String displayName;
        public String name;
        public BigDecimal grossSalary;
        public BigDecimal currentSalary;
    }

    public record SeedResponse(int seeded) {}
}
