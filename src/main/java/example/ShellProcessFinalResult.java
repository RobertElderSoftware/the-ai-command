package example;

import java.io.BufferedInputStream;
import java.io.IOException;

import java.io.IOException;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;

import java.util.Random;


import java.io.File;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.FileOutputStream;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;
import java.lang.ProcessBuilder.Redirect;

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
