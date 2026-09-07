package org.res.ai;

public class ShellProcessFinalResult {
	private ShellProcessPartialResult output = null;
	private int returnValue;

	public ShellProcessFinalResult(ShellProcessPartialResult output, int returnValue) throws Exception {
		this.output = output;
		this.returnValue = returnValue;
	}

	public ShellProcessPartialResult getOutput() throws Exception {
		return this.output;
	}

	public int getReturnValue() throws Exception {
		return this.returnValue;
	}
}
