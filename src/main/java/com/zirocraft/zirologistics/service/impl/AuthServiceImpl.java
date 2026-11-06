package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.UserEntity;
import com.zirocraft.zirologistics.exception.ApiException;
import com.zirocraft.zirologistics.io.request.AuthRequest;
import com.zirocraft.zirologistics.io.response.AuthResponse;
import com.zirocraft.zirologistics.repository.UserRepository;
import com.zirocraft.zirologistics.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final AppUserDetailService appUserDetailService;

    // Standard Keamanan: Minimal 8 karakter, 1 Angka, 1 Huruf Besar
    private static final String PASSWORD_PATTERN = "^(?=.*[0-9])(?=.*[A-Z]).{8,}$";

    /**
     * REGISTER MODULE
     * Menciptakan identitas baru dalam ZiroLogistics Engine.
     */
    @Transactional
    public AuthResponse register(AuthRequest request) {
        log.info("[ZIROCRAFT-AUTH] Initiating registration for: {}", request.getEmail());

        // 1. Validasi Keunikan Email
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            // Obfuscation: Jangan beri tahu kalau email sudah ada jika di public,
            // tapi untuk registrasi kita perlu memberi tahu user agar tidak duplikat.
            throw new ApiException(HttpStatus.CONFLICT, "Identitas email sudah terdaftar dalam sistem.");
        }

        // 2. Strict Server-Side Password Validation
        if (!Pattern.matches(PASSWORD_PATTERN, request.getPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password minimal 8 karakter serta harus memiliki 1 angka dan 1 huruf besar.");
        }

        // 3. Entity Construction
        UserEntity user = UserEntity.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName().toUpperCase()) // Standar Industri: Nama disimpan dalam Uppercase
                .role("ROLE_STAFF")
                .active(true)
                .build();

        UserEntity savedUser = userRepository.save(user);
        log.info("[ZIROCRAFT-AUTH] User {} successfully indexed with role {}", savedUser.getEmail(), savedUser.getRole());

        return mapToResponse(savedUser, null);
    }

    /**
     * LOGIN MODULE
     * Verifikasi kredensial dan enkripsi sesi via JWT.
     */
    public AuthResponse login(AuthRequest request) {
        log.info("[ZIROCRAFT-AUTH] Identity verification attempt: {}", request.getEmail());

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );
        } catch (BadCredentialsException ex) {
            log.warn("[ZIROCRAFT-SECURITY] Login rejected for invalid credentials.");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Email atau password tidak valid.");
        }

        final UserDetails userDetails = appUserDetailService.loadUserByUsername(request.getEmail());
        final String token = jwtUtil.generateToken(userDetails);
        UserEntity user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalStateException("Authenticated user context was not found."));

        log.info("[ZIROCRAFT-AUTH] Authentication Successful: {}", user.getEmail());
        return mapToResponse(user, token);
    }

    /**
     * PRIVATE MAPPER
     * Transformasi Entity ke Response DTO (Clean Architecture).
     */
    private AuthResponse mapToResponse(UserEntity entity, String token) {
        return AuthResponse.builder()
                .publicId(entity.getPublicId())
                .email(entity.getEmail())
                .name(entity.getName())
                .role(entity.getRole())
                .token(token)
                .build();
    }
}