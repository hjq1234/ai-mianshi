package com.ke.nhservice.aimianshi.biz.user;

public record User(Long id, String username, String passwordHash, String nickname, long createdAt) {
}