package com.mogum.archfixture.core;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/fixture")
public record SpringWebInCore(ResponseEntity<Void> response) {
}
