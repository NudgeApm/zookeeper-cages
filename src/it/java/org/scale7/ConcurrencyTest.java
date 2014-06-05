/*
 *  Copyright (c) 2010-2014. LEVEL5
 */

package org.scale7;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import static org.junit.Assert.*;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scale7.zookeeper.cages.ILock;
import org.scale7.zookeeper.cages.ZkCagesException;
import org.scale7.zookeeper.cages.ZkReadLock;
import org.scale7.zookeeper.cages.ZkWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * 
 * @author fabrice
 */
public class ConcurrencyTest {

	private static final int THREAD_COUNT = 10;
	private static final int TIMEOUT_SECONDS = 3;
	private static final String ZOOKEEPER_RESOURCE = "/yop";
	
	private static final Logger LOG = LoggerFactory.getLogger(ConcurrencyTest.class);
	
	@BeforeClass
	public static void setUp() throws IOException {
		AppTest.setUp();
	}
	
	@AfterClass
	public static void tearDown() throws InterruptedException {
		AppTest.tearDown();
	}
	
	private void testLockingOneTask(LockingTask task){
		
		final ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		try {
			
			for (int i=0; i < THREAD_COUNT; i++) {
				executor.execute(task);
			}
			LOG.info("Starting tasks");
			task.start();
			
		} finally {
			
			executor.shutdown();
			try {
				if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.DAYS)) {
					fail("Impossible to stop the executor in " + TIMEOUT_SECONDS + " seconds");
				} 
			} catch(InterruptedException e) {
				fail("The shutdown of the executor was interupted : " + e.getMessage());
			}
			
		}
	}
	
	private void testLockingByTasks(LockingTask... tasks){
		final ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
		try {
			
			for (LockingTask task : tasks) {
				executor.execute(task);
			}
			LOG.info("Starting tasks");
			for (LockingTask task : tasks) {
				task.start();
			}
			
		} finally {
			
			executor.shutdown();
			try {
				if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.DAYS)) {
					fail("Impossible to stop the executor in " + TIMEOUT_SECONDS + " seconds");
				} 
			} catch(InterruptedException e) {
				fail("The shutdown of the executor was interupted : " + e.getMessage());
			}
			
		}
	}
	
	@Test
	public void testReadLocking() {
		
		final LockingTask task  = new ReadLockingTask(null);
		testLockingOneTask(task);
		
		assertTrue(task.getParallelProcessing() > 0);
	}
	
	@Test
	public void testWriteLocking() {
		
		final LockingTask task  = new WriteLockingTask();
		testLockingOneTask(task);
		
		assertTrue(task.getParallelProcessing() == 0);
	}

	@Test
	public void testReadAndWriteLocking() {
		
		final LockingTask readTask1  = new ReadLockingTask(null);
		
		final LockingTask writeTask  = new WriteLockingTask() {
			
			@Override
			protected void makeAssert() {
				super.makeAssert();
				if (readTask1.isProcessing()) {
					fail("The write did not wait the read");
				}
			}
			
		};
		
		final LockingTask readTask2  = new ReadLockingTask(writeTask);
		
		testLockingByTasks(readTask1, writeTask, readTask2, readTask2, readTask2, readTask2, readTask2);
		
		assertTrue(writeTask.getParallelProcessing() == 0);
		assertTrue(readTask2.getParallelProcessing() > 0); // Can fail by chance -> the risk is lower if there is a lot of read tasks
	}

	private static class WriteLockingTask extends LockingTask {
		
		@Override
		protected ILock createLock() {
			return new ZkWriteLock(ZOOKEEPER_RESOURCE);
		}

		@Override
		protected void makeAssert() {
			if (isProcessing()) {
				fail("Another write operation is ongoing");
			}
		}
	}
	
	private static class ReadLockingTask extends LockingTask {

		public ReadLockingTask(LockingTask writeTask) {
			this.writeTask = writeTask;
		}
		
		private final LockingTask writeTask;
		
		@Override
		protected ILock createLock() {
			return new ZkReadLock(ZOOKEEPER_RESOURCE);
		}
		
		@Override
		protected void makeAssert() {
			if (writeTask != null && writeTask.isProcessing()) {
				fail("A write operation is ongoing");
			}
		}
	}

	private static abstract class LockingTask implements Runnable {

		public LockingTask() {
		}
		
		private int parallelProcessing = 0;
		
		private boolean waiting = true;
		private boolean processing = false;
		private boolean done = false;
		
		void start() {
			waiting = false;
		}
		
		protected abstract ILock createLock();
		
		protected abstract void makeAssert();
		
		boolean isProcessing() {
			return processing;
		}
		
		boolean isDone() {
			return done;
		}
		
		int getParallelProcessing() {
			return parallelProcessing;
		}
		
		@Override
		public void run() {
			
			LOG.info("Waiting");
			final ILock lock = createLock();
			
			while (waiting) {
				try {
					Thread.sleep(10);
				} catch (InterruptedException ex) {
					throw new RuntimeException(ex);
				}
			}

			try {
				LOG.info("Acquiring lock");
				lock.acquire();
				LOG.info("Processing");
				if (isProcessing()) {
					parallelProcessing++;
				}
				makeAssert();
				processing = true;
				Thread.sleep(100);
			} catch (ZkCagesException | InterruptedException ex) {
				fail(ex.getMessage());
			} finally {
				processing = false;
				lock.release();
				LOG.info("Lock released");
				done = true;
			}
		}
		
	}
}
