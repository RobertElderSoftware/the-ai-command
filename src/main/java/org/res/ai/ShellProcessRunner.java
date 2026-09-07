package org.res.ai;

import java.io.File;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ShellProcessRunner {
	private List<String> commandParts = null;
	private Map<String, String> environmentVariables = null;
	private File commandWorkingDirectory = null;
	private boolean expectsStdin = false;
	private Process process = null;
	private ExecutorService es = Executors.newFixedThreadPool(2);

	private ShellProcessReaderThread stdOutThread = null;
	private ShellProcessReaderThread stdErrThread = null;

	private Future<byte []> stdOutFuture = null;
	private Future<byte []> stdErrFuture = null;

	public ShellProcessRunner(List<String> commandParts) throws Exception {
		this.commandParts = commandParts;
		this.commonSetup();
	}

	public ShellProcessRunner(List<String> commandParts, Map<String, String> environmentVariables, File commandWorkingDirectory, boolean expectsStdin) throws Exception {
		this.commandParts = commandParts;
		this.environmentVariables = environmentVariables;
		this.commandWorkingDirectory = commandWorkingDirectory;
		this.expectsStdin = expectsStdin;
		this.commonSetup();
	}

	public final void commonSetup() throws Exception {
		ProcessBuilder processBuilder = new ProcessBuilder(this.commandParts);
		if(this.expectsStdin){
			processBuilder.redirectInput(Redirect.PIPE);
		}

		if(this.commandWorkingDirectory != null){
			processBuilder.directory(this.commandWorkingDirectory);
		}

		if(this.environmentVariables != null){
			Map<String, String> env = processBuilder.environment();
			env.clear(); //  Clear existing environment variables.
			for(Map.Entry<String, String> e : environmentVariables.entrySet()){
				env.put(e.getKey(), e.getValue());
			}
		}

		this.process = processBuilder.start();
    
		this.stdOutThread = new ShellProcessReaderThread("stdout", this.process.getInputStream());
		this.stdErrThread = new ShellProcessReaderThread("stderr", this.process.getErrorStream());

		this.stdOutFuture = es.submit(this.stdOutThread);
		this.stdErrFuture = es.submit(this.stdErrThread);
	}

	public OutputStream getOutputStreamForStdin() throws Exception {
		if(this.expectsStdin){
			return process.getOutputStream();
		}else{
			throw new Exception("Was declared with this.expectsStdin, but we're trying to send stdin?");
		}
	}

	public ShellProcessPartialResult getPartialResult() throws Exception{
		/* Call this periodically for a long-running stream process: */
		return new ShellProcessPartialResult(this.stdOutThread.readPartialResult(), this.stdErrThread.readPartialResult());
	}

	public ShellProcessFinalResult getFinalResult() throws Exception{
		/* Call this once to obtain the final result after the process has exited: */
		ShellProcessPartialResult r = new ShellProcessPartialResult(this.stdOutFuture.get(), this.stdErrFuture.get());
		this.es.shutdown();
		int exitCode = process.waitFor();
		return new ShellProcessFinalResult(r, exitCode);
	}
}
