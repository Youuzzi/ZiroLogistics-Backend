package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.UserEntity;
import com.zirocraft.zirologistics.io.request.AuthRequest;
import com.zirocraft.zirologistics.io.response.AuthResponse;
import com.zirocraft.zirologistics.repository.UserRepository;
import com.zirocraft.zirologistics.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final AppUserDetailService appUserDetailService;

    @Transactional
    public AuthResponse register(AuthRequest request) {
        // 1. Validasi Email Unique
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new RuntimeException("Email sudah terdaftar!");
        }

        // 2. Map & Encrypt Password
        UserEntity user = UserEntity.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword())) // ENKRIPSI BCRYPT
                .name(request.getName())
                .role(request.getRole() != null ? request.getRole() : "ROLE_STAFF")
                .active(true)
                .build();

        UserEntity savedUser = userRepository.save(user);

        return mapToResponse(savedUser, null);
    }

    public AuthResponse login(AuthRequest request) {
        // 1. Verifikasi Login via Spring Authentication Manager
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        // 2. Ambil data user & Generate Token
        final UserDetails userDetails = appUserDetailService.loadUserByUsername(request.getEmail());
        final String token = jwtUtil.generateToken(userDetails);

        UserEntity user = userRepository.findByEmail(request.getEmail()).get();

        return mapToResponse(user, token);
    }

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