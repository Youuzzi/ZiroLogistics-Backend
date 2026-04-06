package com.zirocraft.zirologistics.io.response;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class AuthResponse {
    private String publicId;
    private String email;
    private String name;
    private String role;
    private String token; // Bintang utamanya: JWT Token
}