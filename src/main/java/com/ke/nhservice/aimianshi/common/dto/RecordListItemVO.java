package com.ke.nhservice.aimianshi.common.dto;

public record RecordListItemVO(Long id, String position, String company, String domain,
                               String difficulty, String status, Double totalScore,
                               long createdAt, long updatedAt, int dialogueCount) {
}