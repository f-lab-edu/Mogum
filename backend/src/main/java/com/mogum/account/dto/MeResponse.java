package com.mogum.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public record MeResponse(String status, String email, String name,
	@JsonInclude(JsonInclude.Include.NON_NULL) Credit credit) {
	public record Credit(int balance, String nextRefillDate) {
	}
}
