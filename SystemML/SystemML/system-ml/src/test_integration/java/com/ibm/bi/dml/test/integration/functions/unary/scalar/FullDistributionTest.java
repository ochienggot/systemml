/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.test.integration.functions.unary.scalar;

import java.util.HashMap;
import java.util.Random;

import org.junit.Test;

import com.ibm.bi.dml.runtime.matrix.data.MatrixValue.CellIndex;
import com.ibm.bi.dml.test.integration.AutomatedTestBase;
import com.ibm.bi.dml.test.integration.TestConfiguration;
import com.ibm.bi.dml.test.utils.TestUtils;

public class FullDistributionTest extends AutomatedTestBase 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	private final static String TEST_NAME = "DFTest";
	
	enum TEST_TYPE { NORMAL, NORMAL_NOPARAMS, NORMAL_MEAN, NORMAL_SD, F, T, CHISQ, EXP, EXP_NOPARAMS };
	
	private final static String TEST_DIR = "functions/unary/scalar/";

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME, new TestConfiguration(TEST_DIR, TEST_NAME, new String[] { "dfout" }));
	}
	
	@Test
	public void testNormal() {
		runDFTest(TEST_TYPE.NORMAL, true, 1.0, 2.0);
	}
	
	@Test
	public void testNormalNoParams() {
		runDFTest(TEST_TYPE.NORMAL_NOPARAMS, true, null, null);
	}
	
	@Test
	public void testNormalMean() {
		runDFTest(TEST_TYPE.NORMAL_MEAN, true, 1.0, null);
	}
	
	@Test
	public void testNormalSd() {
		runDFTest(TEST_TYPE.NORMAL_SD, true, 2.0, null);
	}
	
	@Test
	public void testT() {
		runDFTest(TEST_TYPE.T, true, 10.0, null);
	}
	
	@Test
	public void testF() {
		runDFTest(TEST_TYPE.T, true, 10.0, 20.0);
	}
	
	@Test
	public void testChisq() {
		runDFTest(TEST_TYPE.CHISQ, true, 10.0, null);
	}
	
	@Test
	public void testExp() {
		runDFTest(TEST_TYPE.EXP, true, 5.0, null);
	}
	
	private void runDFTest(TEST_TYPE type, boolean inverse, Double param1, Double param2) {
		TestConfiguration config = getTestConfiguration(TEST_NAME);

		double in = (new Random(System.nanoTime())).nextDouble();
		
		String HOME = SCRIPT_DIR + TEST_DIR;
		fullDMLScriptName = HOME + TEST_NAME + "_" + type.toString() + ".dml";
		fullRScriptName = HOME + TEST_NAME + "_" + type.toString() + ".R";
		
		String DMLout = HOME + OUTPUT_DIR + "dfout";
		String Rout = HOME + EXPECTED_DIR + "dfout";
		
		switch(type) {
		case NORMAL_NOPARAMS:
			programArgs = new String[]{"-args", Double.toString(in), DMLout };
			rCmd = "Rscript" + " " + fullRScriptName + " " + Double.toString(in) + " " + Rout;
			break;
			
		case NORMAL_MEAN:
		case NORMAL_SD:
		case T:
		case CHISQ:
		case EXP:
			programArgs = new String[]{"-args", Double.toString(in), Double.toString(param1), DMLout };
			rCmd = "Rscript" + " " + fullRScriptName + " " + Double.toString(in) + " " + Double.toString(param1) + " " + Rout;
			break;
			
		case NORMAL:
		case F:
			programArgs = new String[]{"-args", Double.toString(in), Double.toString(param1), Double.toString(param2), DMLout };
			rCmd = "Rscript" + " " + fullRScriptName + " " + Double.toString(in) + " " + Double.toString(param1) + " " + Double.toString(param2) + " " + Rout;
			break;
		
			default: 
				throw new RuntimeException("Invalid distribution function: " + type);
		}
		
		loadTestConfiguration(config);
		
		runTest(true, false, null, -1); 
		runRScript(true); 
		
		HashMap<CellIndex, Double> dmlfile = readDMLMatrixFromHDFS("dfout");
		HashMap<CellIndex, Double> rfile  = readRMatrixFromFS("dfout");
		TestUtils.compareMatrices(dmlfile, rfile, 1e-8, "DMLout", "Rout");

	}
	
}
