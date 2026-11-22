package com.mogum.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 로그인 뒤 Google API를 쓰지 않으므로 세션/메모리 양쪽의 기본 토큰 저장소를 대체한다. */
public final class DiscardingOAuth2AuthorizedClients
	implements OAuth2AuthorizedClientRepository, OAuth2AuthorizedClientService {
	@Override
	public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId, Authentication principal,
		HttpServletRequest request) {
		return null;
	}

	@Override
	public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
		// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
		HttpServletRequest request, HttpServletResponse response) {}
	// @checkstyle:on

	@Override
	public void removeAuthorizedClient(String registrationId, Authentication principal,
		// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
		HttpServletRequest request, HttpServletResponse response) {}
	// @checkstyle:on

	@Override
	public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId, String principalName) {
		return null;
	}

	@Override
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal) {}
	// @checkstyle:on

	@Override
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	public void removeAuthorizedClient(String registrationId, String principalName) {}
	// @checkstyle:on
}
