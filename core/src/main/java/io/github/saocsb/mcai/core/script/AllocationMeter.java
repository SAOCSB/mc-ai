package io.github.saocsb.mcai.core.script;

import java.lang.management.ManagementFactory;

/**
 * Bytes allocated by the current thread, from the HotSpot thread MXBean. Unavailable (-1) on JVMs
 * without per-thread allocation accounting.
 */
final class AllocationMeter {
	private static final com.sun.management.ThreadMXBean BEAN = find();

	private AllocationMeter() {
	}

	private static com.sun.management.ThreadMXBean find() {
		try {
			if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
					&& bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()) {
				return bean;
			}
		} catch (RuntimeException | LinkageError ignored) {
			// no allocation accounting on this JVM
		}
		return null;
	}

	/** Total bytes allocated by the calling thread so far, or -1 when unknown. */
	static long currentThreadAllocatedBytes() {
		return BEAN == null ? -1 : BEAN.getCurrentThreadAllocatedBytes();
	}
}
