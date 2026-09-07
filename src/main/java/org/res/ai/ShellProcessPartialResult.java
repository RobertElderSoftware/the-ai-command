package org.res.ai;

public class ShellProcessPartialResult {

	private byte [] stdout = null;
	private byte [] stderr = null;

	public ShellProcessPartialResult(byte [] stdout, byte [] stderr) throws Exception {
		this.stdout = stdout;
		this.stderr = stderr;
	}

	public byte [] getStdoutOutput() throws Exception {
		return this.stdout;
	}

	public byte [] getStderrOutput() throws Exception {
		return this.stderr;
	}
}
