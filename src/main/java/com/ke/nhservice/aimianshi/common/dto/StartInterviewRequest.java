package com.ke.nhservice.aimianshi.common.dto;

public record StartInterviewRequest(Long resumeId, String position, String company,
                                    String domain, String difficulty) {
}