package com.neoproc.financialagent.testingharness.controller;

import com.neoproc.financialagent.testingharness.model.Employee;
import com.neoproc.financialagent.testingharness.model.EmployeeRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Read + write flow for the mock payroll table. GET /employees renders a
 * per-row salary input; POST /employees/submit echoes back what was
 * received along with the server-side sum so the caller (agent under
 * test) can reconcile its own running total against the portal's.
 *
 * <p>Salary inputs are keyed by the employee's display ID (with hyphens,
 * e.g. {@code salary[1-0909-0501]=1234567}), which is what the agent
 * sees on screen — the server accepts the same string it renders.
 */
@Controller
public class EmployeeController {

    @GetMapping("/employees")
    public String employees(Model model) {
        List<Employee> employees = EmployeeRegistry.EMPLOYEES;
        BigDecimal currentTotal = employees.stream()
                .map(Employee::currentSalary)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        model.addAttribute("employees", employees);
        model.addAttribute("currentTotal", currentTotal);
        return "employees";
    }

    @PostMapping("/employees/submit")
    public String submit(@RequestParam Map<String, String> params, Model model) {
        List<Employee> employees = EmployeeRegistry.EMPLOYEES;
        BigDecimal submittedTotal = BigDecimal.ZERO;  // sum of changed rows only
        BigDecimal grandTotal = BigDecimal.ZERO;      // payroll after submission
        int updatedCount = 0;
        for (Employee e : employees) {
            String paramKey = "salary[" + e.id() + "]";
            String raw = params.get(paramKey);
            BigDecimal valueForGrand = e.currentSalary();
            if (raw != null && !raw.isBlank()) {
                try {
                    BigDecimal submitted = new BigDecimal(raw.replace(",", "").trim());
                    if (submitted.compareTo(e.currentSalary()) != 0) {
                        submittedTotal = submittedTotal.add(submitted);
                        updatedCount++;
                    }
                    valueForGrand = submitted;
                } catch (NumberFormatException nfe) {
                    // Bad input — fall back to the existing salary.
                }
            }
            grandTotal = grandTotal.add(valueForGrand);
        }
        submittedTotal = submittedTotal.setScale(2, RoundingMode.HALF_UP);
        grandTotal = grandTotal.setScale(2, RoundingMode.HALF_UP);
        model.addAttribute("submittedTotal", submittedTotal);
        model.addAttribute("grandTotal", grandTotal);
        model.addAttribute("updatedCount", updatedCount);
        return "confirmation";
    }
}
