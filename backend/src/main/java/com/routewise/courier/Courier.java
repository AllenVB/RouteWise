package com.routewise.courier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Courier profile — one per {@code users} row with role COURIER. Schema owned by Liquibase. */
@Entity
@Table(name = "couriers")
public class Courier {

    public static final String AVAILABLE = "AVAILABLE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "employee_code")
    private String employeeCode;

    /** AVAILABLE · ON_ROUTE · OFF_DUTY ({@code ck_couriers_status}). */
    @Column(nullable = false)
    private String status = AVAILABLE;

    protected Courier() {}

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getEmployeeCode() { return employeeCode; }
    public String getStatus() { return status; }
}
