package uk.co.datumedge.redislauncher;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.integration.junit4.JMock;
import org.jmock.integration.junit4.JUnit4Mockery;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JMock.class)
public class DestroyOnErrorLifecyclePolicyTest {
	private final Mockery context = new JUnit4Mockery();
	private final RedisServer server = context.mock(RedisServer.class);
	private final DestroyOnErrorLifecyclePolicy lifecyclePolicy = new DestroyOnErrorLifecyclePolicy();

	@Test
	public void destroysServerOnFailureToStart() {
		expectServerDestroy();
		lifecyclePolicy.failedToStart(server);
	}

	@Test
	public void destroysServerOnFailureToStop() {
		expectServerDestroy();
		lifecyclePolicy.failedToStop(server);
	}

	private void expectServerDestroy() {
		context.checking(new Expectations() {{
			oneOf(server).destroy();
		}});
	}
}