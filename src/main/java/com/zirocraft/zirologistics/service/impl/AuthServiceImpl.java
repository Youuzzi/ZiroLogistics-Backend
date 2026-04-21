package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.UserEntity;
import com.zirocraft.zirologistics.io.request.AuthRequest;
import com.zirocraft.zirologistics.io.response.AuthResponse;
import com.zirocraft.zirologistics.repository.UserRepository;
import com.zirocraft.zirologistics.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
            throw new RuntimeException("REGISTRATION_FAILED: Identitas email sudah terdaftar dalam sistem.");
        }

        // 2. Strict Server-Side Password Validation
        if (!Pattern.matches(PASSWORD_PATTERN, request.getPassword())) {
            throw new RuntimeException("SECURITY_VIOLATION: Password tidak memenuhi kriteria kekuatan (Min 8 Karakter, 1 Angka, 1 Huruf Besar).");
        }

        // 3. Entity Construction
        UserEntity user = UserEntity.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName().toUpperCase()) // Standar Industri: Nama disimpan dalam Uppercase
                .role(request.getRole() != null ? request.getRole() : "ROLE_STAFF")
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
            // 1. Spring Security Authentication
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );

            // 2. Identity Retrieval
            final UserDetails userDetails = appUserDetailService.loadUserByUsername(request.getEmail());

            // 3. JWT Generation
            final String token = jwtUtil.generateToken(userDetails);

            UserEntity user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new RuntimeException("CORE_ERROR: User context lost during session sync."));

            log.info("[ZIROCRAFT-AUTH] Authentication Successful: {}", user.getEmail());
            return mapToResponse(user, token);

        } catch (BadCredentialsException e) {
            log.error("[ZIROCRAFT-SECURITY] Login failed for {}: Invalid Credentials", request.getEmail());
            // SECURITY OBFUSCATION: Pesan error dibuat generik untuk mencegah user enumeration.
            throw new RuntimeException("AUTHENTICATION_FAILED: Email atau Password tidak valid.");
        } catch (Exception e) {
            log.error("[ZIROCRAFT-SECURITY] System error during login: {}", e.getMessage());
            throw new RuntimeException("CORE_ERROR: Kegagalan otentikasi internal.");
        }
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