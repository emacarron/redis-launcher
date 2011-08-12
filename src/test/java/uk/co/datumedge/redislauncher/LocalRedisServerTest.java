package uk.co.datumedge.redislauncher;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.core.IsSame.sameInstance;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.TimeUnit;

import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.integration.junit4.JMock;
import org.jmock.integration.junit4.JUnit4Mockery;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

@RunWith(JMock.class)
public class LocalRedisServerTest {
	private static final int TIMEOUT = 60000;
	private final Mockery context = new JUnit4Mockery();
	private final LifecyclePolicy mockLifecyclePolicy = context.mock(LifecyclePolicy.class);
	private final LocalRedisServer server;
	private final Execution processBuilder;
	private final MBeanServer mBeanServer = MBeanServerFactory.newMBeanServer();
	private final ObjectName objectName;

	public LocalRedisServerTest() throws MalformedObjectNameException {
		String command = System.getProperty(LocalRedisServer.COMMAND_PROPERTY);
		if (command == null) {
			Assert.fail(LocalRedisServer.COMMAND_PROPERTY + " system property must be a path to a redis-server executable");
		}
		processBuilder = new Execution(new CommandLine(command));
		server = new LocalRedisServer(processBuilder);
		objectName = new ObjectName("uk.co.datumedge.redislauncher:type=LocalRedisServer,name=Test");
	}

	@Before
	public void setup() {
		checkServerIsStopped();
		deleteDatastore();
	}

	private void checkServerIsStopped() {
		Jedis jedis = null;
		try {
			jedis = new Jedis("localhost");
			jedis.ping();
			fail("Server is running before test has started");
		} catch (JedisConnectionException e) {
			// expected
		} finally {
			if (jedis != null) {
				jedis.disconnect();
			}
		}
	}

	private void deleteDatastore() {
		new File("dump.rdb").delete();
	}

	@After
	public void stopAndDestroyServer() throws IOException, InterruptedException {
		try {
			server.stop();
		} finally {
			server.destroy();
		}
	}

	@Test
	public void canStartServerInstantiatedUsingSystemProperty() throws IOException, InterruptedException {
		LocalRedisServer server = LocalRedisServer.newInstance();
		try {
			server.start();
		} finally {
			server.stop();
		}
	}

	@Test(expected=NullPointerException.class)
	public void throwsNullPointerExceptionIfSystemPropertyIsAbsentWhenInstantiatingServer() {
		String command = System.getProperty(LocalRedisServer.COMMAND_PROPERTY);
		try {
			System.clearProperty(LocalRedisServer.COMMAND_PROPERTY);
			LocalRedisServer.newInstance();
		} finally {
			System.setProperty(LocalRedisServer.COMMAND_PROPERTY, command);
		}
	}

	@Test
	public void canBeConnectedToOnceStarted() throws IOException, InterruptedException {
		Jedis jedis = null;
		try {
			server.start();
			jedis = new Jedis("localhost");
			assertThat(jedis.ping(), is("PONG"));
		} finally {
			jedis.disconnect();
		}
	}

	@Test(expected=JedisConnectionException.class)
	public void cannotBeConnectedToOnceStopped() throws IOException, InterruptedException {
		server.start();
		server.stop();
		pingServer();
	}

	@Test
	public void stopDoesNothingWhenServerNotStarted() throws IOException, InterruptedException {
		server.stop();
	}

	@Test
	public void startDoesNothingWhenServerAlreadyStarted() throws IOException, InterruptedException {
		server.start();
		server.start();
	}

	@Test(expected=IOException.class)
	public void throwsIOExceptionWhenStartingIfCommandDoesNotExist() throws IOException, InterruptedException {
		RedisServer server = new LocalRedisServer(new Execution(new CommandLine("java")));
		try {
			server.start();
		} finally {
			server.stop();
		}
	}

	@Test(expected=ConnectException.class)
	public void throwsConnectExceptionIfFailedToConnect() throws InterruptedException, IOException {
		RedisServer server = new LocalRedisServer(new Execution(new CommandLine("java")));

		try {
			server.start();
		} finally {
			try {
				server.stop();
			} catch (IOException e) {
				// ignore
			}
		}
	}

	@Test
	public void callsLifecyclePolicyWhenFailedToConnect() throws IOException, InterruptedException {
		ConnectionProperties connectionProperties = new ConnectionProperties.Builder()
			.withMaximumConnectionAttempts(1)
			.build();

		final RedisServer server = new LocalRedisServer(
				new Execution(new CommandLine("java")),
				connectionProperties,
				mockLifecyclePolicy);

		context.checking(new Expectations() {{
			allowing(mockLifecyclePolicy).getProcessDestroyer(); will(returnValue(NullProcessDestroyer.INSTANCE));
			oneOf(mockLifecyclePolicy).failedToStart(with(sameInstance(server)));
		}});

		try {
			server.start();
		} catch (ConnectException e) {
			// expected
		} finally {
			try {
				server.stop();
			} catch (IOException e) {
				// ignore
			}
		}
	}

	@Test
	public void canStartServerAgainAfterServerIsStopped() throws IOException, InterruptedException {
		server.start();
		server.stop();
		server.start();
	}

	@Test(timeout=TIMEOUT)
	public void startBlocksUntilServerCanAcceptRequests() throws IOException, InterruptedException {
		populateServerWithLargeDataSet();

		Jedis jedis = null;
		try {
			server.start();
			jedis = new Jedis("localhost");
			assertThat(jedis.get("RedisServerTestKey0_0"), is(equalTo("value")));
		} finally {
			if (jedis != null) {
				jedis.disconnect();
			}
		}
	}

	@Test(expected=ServerNotReadyException.class, timeout=TIMEOUT)
	public void throwsServerNotReadyExceptionWhenNotReadyToAcceptRequestsAfterTimeout() throws IOException, InterruptedException {
		populateServerWithLargeDataSet();

		LocalRedisServer server = redisServerWithOnlyOneReadinessAttempt();
		try {
			server.start();
		} finally {
			waitForServerToAcceptRequests();
			server.stop();
		}
	}

	@Test(timeout=TIMEOUT)
	public void callsLifecyclePolicyWhenNotReadyToAcceptRequestsAfterTimeout() throws IOException, InterruptedException {
		populateServerWithLargeDataSet();

		ConnectionProperties connectionProperties = new ConnectionProperties.Builder()
				.withMaximumReadinessAttempts(1)
				.build();

		final RedisServer server = new LocalRedisServer(processBuilder, connectionProperties, mockLifecyclePolicy);

		context.checking(new Expectations() {{
			allowing(mockLifecyclePolicy).getProcessDestroyer(); will(returnValue(NullProcessDestroyer.INSTANCE));
			oneOf(mockLifecyclePolicy).failedToStart(with(sameInstance(server)));
		}});

		try {
			server.start();
		} catch (ServerNotReadyException e) {
			// expected
		} finally {
			waitForServerToAcceptRequests();
			server.stop();
		}
	}

	private void waitForServerToAcceptRequests() throws InterruptedException {
		Jedis jedis = new Jedis("localhost");
		try {
			while (true) {
				try {
					if ("PONG".equals(jedis.ping())) {
						return;
					}
					TimeUnit.MILLISECONDS.sleep(1000);
				} catch (JedisDataException e) {
					// ignore
				}
			}
		} catch (JedisConnectionException e) {
			// ignore
		} finally {
			jedis.disconnect();
		}
	}

	@Test
	public void destroyDoesNothingIfServerNotStarted() {
		server.destroy();
	}

	@Test(expected=JedisConnectionException.class)
	public void canBeDestroyedWhenServerIsStartedButNotReadyToAcceptRequests() throws IOException, InterruptedException {
		populateServerWithLargeDataSet();

		LocalRedisServer server = redisServerWithOnlyOneReadinessAttempt();
		try {
			server.start();
			fail("Did not thrown ServerNotReadyException");
		} catch (ServerNotReadyException e) {
			server.destroy();
			pingForFiveSeconds();
		}
	}

	private LocalRedisServer redisServerWithOnlyOneReadinessAttempt() {
		return new LocalRedisServer(
				processBuilder,
				new ConnectionProperties.Builder()
						.withMaximumReadinessAttempts(1)
						.build(),
				new KeepRunningOnErrorLifecyclePolicy());
	}

	private void pingForFiveSeconds() throws InterruptedException {
		Jedis jedis = new Jedis("localhost");
		try {
			for (int i=0; i<50; i++) {
				try {
					jedis.ping();
				} catch (JedisDataException e) {
					// ignored
				}
				TimeUnit.MILLISECONDS.sleep(100);
			}
		} finally {
			jedis.disconnect();
		}
	}

	@Test(timeout=TIMEOUT)
	public void callsLifecyclePolicyIfWaitingForProcessExitTimesOut() throws IOException, InterruptedException {
		populateServerWithLargeDataSet();

		final LocalRedisServer server = new LocalRedisServer(
				processBuilder,
				new ConnectionProperties.Builder()
					.withShutdownTimeoutMillis(1L)
					.build(),
				mockLifecyclePolicy);

		context.checking(new Expectations() {{
			allowing(mockLifecyclePolicy).getProcessDestroyer(); will(returnValue(NullProcessDestroyer.INSTANCE));
			oneOf(mockLifecyclePolicy).failedToStop(server);
		}});

		try {
			server.start();
		} finally {
			try {
				server.stop();
			} finally {
				server.destroy();
			}
		}
	}

	@Test(timeout=TIMEOUT)
	public void callsLifecyclePolicyIfFailedToSendShutdownCommand() throws IOException, InterruptedException {
		final RedisServer server = new LocalRedisServer(processBuilder, ConnectionProperties.DEFAULT, mockLifecyclePolicy);

		context.checking(new Expectations() {{
			allowing(mockLifecyclePolicy).getProcessDestroyer(); will(returnValue(NullProcessDestroyer.INSTANCE));
			oneOf(mockLifecyclePolicy).failedToStop(server);
		}});

		Jedis jedis = null;
		try {
			jedis = new Jedis("localhost");
			server.start();
			jedis.shutdown();
			server.stop();
		} finally {
			if (jedis != null) {
				jedis.disconnect();
			}
		}
	}

	private void populateServerWithLargeDataSet() throws IOException, InterruptedException {
		Jedis jedis = null;
		try {
			server.start();
			jedis = new Jedis("localhost");
			for (int i=0; i<1000; i++) {
				Pipeline pipeline = jedis.pipelined();
				for (int j=0; j<1000; j++) {
					pipeline.set("RedisServerTestKey" + i + "_" + j, "value");
					pipeline.exec();
				}
			}
		} finally {
			if (jedis != null) {
				jedis.disconnect();
			}
			server.stop();
		}
	}

	@Test
	public void serverCanBeStartedUsingJmx() throws JMException, IOException, InterruptedException {
		mBeanServer.registerMBean(server, objectName);

		Jedis jedis = null;
		try {
			invokeMBeanOperation("start");
			jedis = new Jedis("localhost");
			assertThat(jedis.ping(), is("PONG"));
		} finally {
			jedis.disconnect();
			server.stop();
		}
	}

	@Test(expected=JedisConnectionException.class)
	public void serverCanBeStoppedUsingJmx() throws JMException, IOException, InterruptedException {
		mBeanServer.registerMBean(server, objectName);

		try {
			server.start();
		} finally {
			invokeMBeanOperation("stop");
			pingServer();
		}
	}

	private void pingServer() {
		Jedis jedis = new Jedis("localhost");
		try {
			jedis.ping();
		} finally {
			jedis.disconnect();
		}
	}

	private void invokeMBeanOperation(String operation) throws ReflectionException, InstanceNotFoundException, MBeanException {
		mBeanServer.invoke(objectName, operation, new Object[0], new String[0]);
	}

	@Test(expected=JedisConnectionException.class)
	public void stopsServerWhenJavaProcessIsKilled() throws IOException, InterruptedException {
		try {
			CommandLine commandLine = new CommandLine("java")
					.addArguments(new String[]{"-cp", System.getProperty("java.class.path")})
					.addArgument(String.format("-D%s=%s", LocalRedisServer.COMMAND_PROPERTY, System.getProperty(LocalRedisServer.COMMAND_PROPERTY)))
					.addArgument(ForkedRedisServer.class.getCanonicalName());

			DefaultExecutor executor = new DefaultExecutor();
			ExecuteWatchdog watchdog = new ExecuteWatchdog(1000L);
			executor.setWatchdog(watchdog);
			try {
				executor.execute(commandLine);
				fail("Expected watchdog to terminate process");
			} catch (ExecuteException e) {
				assertThat(watchdog.killedProcess(), is(true));
			}

			pingServer();
		} finally {
			try {
				Jedis jedis = new Jedis("localhost");
				try {
					jedis.shutdown();
				} finally {
					jedis.disconnect();
				}
			} catch (JedisConnectionException e) {
				// will happen if forked process correctly killed the redis-server process
			}
		}
	}
}
