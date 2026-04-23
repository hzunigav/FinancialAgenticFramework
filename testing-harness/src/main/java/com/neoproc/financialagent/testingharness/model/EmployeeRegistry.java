package com.neoproc.financialagent.testingharness.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Hardcoded employee roster for the mock payroll table. Mirrors how the
 * real INS portal renders the planilla: cédula in {@code D-NNNNNNNNN}
 * format, full name in uppercase preserving Spanish diacritics
 * ({@code Ñ}, accents). Names and IDs deliberately include {@code Ñ} and
 * accented vowels so the agent's {@link com.neoproc.financialagent.common.match.EmployeeMatcher}
 * exercises its diacritic-folding path.
 */
public final class EmployeeRegistry {

    private EmployeeRegistry() {}

    public static final List<Employee> EMPLOYEES = List.of(
            new Employee("0-110080108", "OLGER ULATE ROJAS",                new BigDecimal("750000.00")),
            new Employee("0-114230204", "CARLOS ANDRES MONTERO ARCE",       new BigDecimal("920000.00")),
            new Employee("0-116360868", "VERONICA ROJAS SANCHEZ",           new BigDecimal("680000.00")),
            new Employee("0-118040420", "DAPHNE GABRIELA MORA HERNANDEZ",   new BigDecimal("845000.00")),
            new Employee("0-118920764", "ASHLEY VALERIA BRENES ARRONES",    new BigDecimal("710000.00")),
            new Employee("0-118860150", "FRANCISCO JOSE OCHOA MEDINA",      new BigDecimal("1150000.00")),
            new Employee("0-111940073", "PABLO ROBERTO UREÑA GARCIA",       new BigDecimal("1280000.00")),
            new Employee("0-118240826", "GLORIANA SALAS MENDEZ",            new BigDecimal("795000.00")),
            new Employee("0-205030093", "RAFAEL ANTONIO MONTERO MENDEZ",    new BigDecimal("1050000.00")),
            new Employee("0-108260501", "FERNANDO JOSE UREÑA MORA",         new BigDecimal("1340000.00")),
            new Employee("0-115010333", "LUIS ALEJANDRO CASCANTE CALDERON", new BigDecimal("970000.00")),
            new Employee("0-116310468", "DIEGO RAFAEL VADO MONGE",          new BigDecimal("885000.00")),
            new Employee("0-117040400", "ANDRES MARIN MUÑOZ",               new BigDecimal("760000.00")),
            new Employee("0-117740264", "YANKO DANIEL SOLANO PICADO",       new BigDecimal("830000.00")),
            new Employee("0-207630807", "EVELYN NATALIA GODINEZ BOZA",      new BigDecimal("905000.00"))
    );
}
