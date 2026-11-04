package ro.distr2pl.app.dto;

import java.math.BigDecimal;

public class CreateUserRequest {
    private String username;
    private String email;
    private String fullName;
    private BigDecimal balance;

    public CreateUserRequest() {
    }

    public CreateUserRequest(String username, String email, String fullName, BigDecimal balance) {
        this.username = username;
        this.email = email;
        this.fullName = fullName;
        this.balance = balance;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }
}

