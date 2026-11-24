package com.mogum.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import com.mogum.card.domain.FsrsState;
import com.mogum.support.IntegrationTest;

@IntegrationTest
class HttpJsonIntegrationTest {
	@Autowired
	private RequestMappingHandlerAdapter requestMappingHandlerAdapter;

	@Test
	@DisplayName("Jackson 2와 3가 함께 있어도 운영 MVC의 JSON 요청과 응답은 Jackson 3를 쓴다")
	void usesJackson3ForHttpJson() {
		// when
		var converters = requestMappingHandlerAdapter.getMessageConverters();

		// then
		assertThat(converters).filteredOn(converter -> converter.canRead(FsrsState.class, MediaType.APPLICATION_JSON))
			.singleElement().isInstanceOf(JacksonJsonHttpMessageConverter.class);
		assertThat(converters).filteredOn(converter -> converter.canWrite(FsrsState.class, MediaType.APPLICATION_JSON))
			.singleElement().isInstanceOf(JacksonJsonHttpMessageConverter.class);
	}
}
