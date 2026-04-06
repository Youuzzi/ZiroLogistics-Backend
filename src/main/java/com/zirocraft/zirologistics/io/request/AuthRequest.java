package com.zirocraft.zirologistics.io.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AuthRequest {
    @NotBlank @Email
    private String email;
    @NotBlank
    private String password;
    private String name; // Dipakai pas Register
    private String role; // Dipakai pas Register
}