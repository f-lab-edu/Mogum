package com.mogum.archfixture.worker;

import jakarta.transaction.Transactional;

public class JakartaTransactionalWorkerMethod {
	@Transactional
	// @checkstyle:off — 포매터가 빈 선언과 블록을 한 줄로 유지한다.
	public void run() {}
	// @checkstyle:on
}
