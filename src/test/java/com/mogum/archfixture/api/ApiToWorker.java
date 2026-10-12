package com.mogum.archfixture.api;

import com.mogum.archfixture.worker.WorkerPort;

public record ApiToWorker(WorkerPort worker) {
}
