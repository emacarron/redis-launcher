package uk.co.datumedge.redislauncher;

import java.io.IOException;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.ProcessDestroyer;
import org.apache.commons.exec.PumpStreamHandler;

public final class Execution {
	private final CommandLine commandLine;
	private ExecutionProcessDestroyer executionProcessDestroyer;

	public Execution(CommandLine commandLine) {
		this.commandLine = commandLine;
	}

	DefaultExecuteResultHandler start(ProcessDestroyer lifecyleProcessDestroyer) throws IOException {
		Executor executor = new DefaultExecutor();
		DefaultExecuteResultHandler handler = new DefaultExecuteResultHandler();
		this.executionProcessDestroyer = new ExecutionProcessDestroyer();
		executor.setProcessDestroyer(new CompositeProcessDestroyer(this.executionProcessDestroyer, lifecyleProcessDestroyer));
		executor.setStreamHandler(new PumpStreamHandler(null, null));
		executor.execute(commandLine, handler);
		return handler;
	}

	void destroy() {
		if (executionProcessDestroyer != null) {
			executionProcessDestroyer.destroy();
		}
	}
}
