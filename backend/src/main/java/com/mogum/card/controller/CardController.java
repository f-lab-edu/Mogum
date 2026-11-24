package com.mogum.card.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.mogum.auth.AccountPrincipal;
import com.mogum.card.domain.Rating;
import com.mogum.card.dto.CardReviewRequest;
import com.mogum.card.service.CardCommandService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CardController {

	private final CardCommandService cardCommandService;

	@PostMapping("/api/cards/{id}/reviews")
	public ResponseEntity<Void> review(
		@AuthenticationPrincipal
		AccountPrincipal.Member member,
		@PathVariable
		long id,
		@Valid
		@RequestBody
		CardReviewRequest request) {
		cardCommandService.review(member.userId(), id, Rating.valueOf(request.rating()));
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/api/cards/{id}")
	public ResponseEntity<Void> delete(
		@AuthenticationPrincipal
		AccountPrincipal.Member member,
		@PathVariable
		long id) {
		cardCommandService.delete(member.userId(), id);
		return ResponseEntity.noContent().build();
	}
}
