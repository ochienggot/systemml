/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */


package com.ibm.bi.dml.runtime.matrix.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math3.random.Well1024a;

import com.ibm.bi.dml.hops.DataGenOp;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.controlprogram.parfor.stat.Timing;
import com.ibm.bi.dml.runtime.util.NormalPRNGenerator;
import com.ibm.bi.dml.runtime.util.PRNGenerator;
import com.ibm.bi.dml.runtime.util.PoissonPRNGenerator;
import com.ibm.bi.dml.runtime.util.UniformPRNGenerator;

/**
 *  
 */
public class LibMatrixDatagen 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	protected static final Log LOG = LogFactory.getLog(LibMatrixDatagen.class.getName());
	
	public static final String RAND_PDF_UNIFORM = "uniform";
	public static final String RAND_PDF_NORMAL = "normal";
	public static final String RAND_PDF_POISSON = "poisson";
	
	private LibMatrixDatagen() {
		//prevent instantiation via private constructor
	}
	
	/**
	 * 
	 * @param min
	 * @param max
	 * @param sparsity
	 * @param pdf
	 * @return
	 */
	public static boolean isShortcutRandOperation( double min, double max, double sparsity, String pdf )
	{
		return pdf.equalsIgnoreCase(RAND_PDF_UNIFORM)
			   && (  ( min == 0.0 && max == 0.0 ) //all zeros
				   ||( sparsity==1.0d && min == max )); //equal values
	}
	
	/**
	 * A matrix of random numbers is generated by using multiple seeds, one for each 
	 * block. Such block-level seeds are produced via Well equidistributed long-period linear 
	 * generator (Well1024a). For a given seed, this function sets up the block-level seeds.
	 * 
	 * This function is invoked from both CP (RandCPInstruction.processInstruction()) 
	 * as well as MR (RandMR.java while setting up the Rand job).
	 * 
	 * @param seed
	 * @return
	 */
	public static Well1024a setupSeedsForRand(long seed) 
	{
		long lSeed = (seed == DataGenOp.UNSPECIFIED_SEED ? DataGenOp.generateRandomSeed() : seed);
		LOG.trace("Setting up RandSeeds with initial seed = "+lSeed+".");

		Random random=new Random(lSeed);
		Well1024a bigrand=new Well1024a();
		//random.setSeed(lSeed);
		int[] seeds=new int[32];
		for(int s=0; s<seeds.length; s++)
			seeds[s]=random.nextInt();
		bigrand.setSeed(seeds);
		
		return bigrand;
	}
	
	/**
	 * 
	 * @param nrow
	 * @param ncol
	 * @param brlen
	 * @param bclen
	 * @param sparsity
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static long[] computeNNZperBlock(long nrow, long ncol, int brlen, int bclen, double sparsity) throws DMLRuntimeException {
		int numBlocks = (int) (Math.ceil((double)nrow/brlen) * Math.ceil((double)ncol/bclen));
		//System.out.println("nrow=" + nrow + ", brlen=" + brlen + ", ncol="+ncol+", bclen=" + bclen + "::: " + Math.ceil(nrow/brlen));
		
		// CURRENT: 
		// 		Total #of NNZ is set to the expected value (nrow*ncol*sparsity).
		// TODO: 
		//		Instead of using the expected value, one should actually 
		// 		treat NNZ as a random variable and accordingly generate a random value.
		long nnz = (long) Math.ceil (nrow * (ncol*sparsity));
		//System.out.println("Number of blocks = " + numBlocks + "; NNZ = " + nnz);

		if ( numBlocks > Integer.MAX_VALUE ) {
			throw new DMLRuntimeException("A random matrix of size [" + nrow + "," + ncol + "] can not be created. Number of blocks (" +  numBlocks + ") exceeds the maximum integer size. Try to increase the block size.");
		}
		
		// Compute block-level NNZ
		long[] ret  = new long[numBlocks];
		Arrays.fill(ret, 0);
		
		if ( nnz < numBlocks ) {
			// Ultra-sparse matrix
			
			// generate the number of blocks with at least one non-zero
			// = a random number between [1,nnz]
			Random runif = new Random(System.nanoTime());
			int numNZBlocks = 1 + runif.nextInt((int)(nnz-1));
			
			// distribute non-zeros across numNZBlocks

			// compute proportions for each nzblock 
			// - divide (0,1] interval into numNZBlocks portions of random size
			double[] blockNNZproportions = new double[numNZBlocks];
			
			runif.setSeed(System.nanoTime());
			for(int i=0; i < numNZBlocks-1; i++) {
				blockNNZproportions[i] = runif.nextDouble();
			}
			blockNNZproportions[numNZBlocks-1] = 1;
			// sort the values in ascending order
			Arrays.sort(blockNNZproportions);
			
			// compute actual number of non zeros per block according to proportions
			long actualnnz = 0;
			int bid;
			runif.setSeed(System.nanoTime());
			for(int i=0; i < numNZBlocks; i++) {
				bid = -1;
				do {
					bid = runif.nextInt(numBlocks);
				} while( ret[bid] != 0);
				
				double prop = (i==0 ? blockNNZproportions[i]: (blockNNZproportions[i] - blockNNZproportions[i-1]));
				ret[bid] = (long)Math.floor(prop * nnz);
				actualnnz += ret[bid];
			}
			
			// Code to make sure exact number of non-zeros are generated
			while (actualnnz < nnz) {
				bid = runif.nextInt(numBlocks);
				ret[bid]++;
				actualnnz++;
			}
		}
		else {
			int bid = 0;
			
			//long actualnnz = 0;
			for(long r = 0; r < nrow; r += brlen) {
				long curBlockRowSize = Math.min(brlen, (nrow - r));
				for(long c = 0; c < ncol; c += bclen)
				{
					long curBlockColSize = Math.min(bclen, (ncol - c));
					ret[bid] = (long) (curBlockRowSize * curBlockColSize * sparsity);
					//actualnnz += ret[bid];
					bid++;
				}
			}
		}
		return ret;
	}
	
	/**
	 * Function to generate a matrix of random numbers. This is invoked both
	 * from CP as well as from MR. In case of CP, it generates an entire matrix
	 * block-by-block. A <code>bigrand</code> is passed so that block-level
	 * seeds are generated internally. In case of MR, it generates a single
	 * block for given block-level seed <code>bSeed</code>.
	 * 
	 * When pdf="uniform", cell values are drawn from uniform distribution in
	 * range <code>[min,max]</code>.
	 * 
	 * When pdf="normal", cell values are drawn from standard normal
	 * distribution N(0,1). The range of generated values will always be
	 * (-Inf,+Inf).
	 * 
	 * @param rows
	 * @param cols
	 * @param rowsInBlock
	 * @param colsInBlock
	 * @param sparsity
	 * @param min
	 * @param max
	 * @param bigrand
	 * @param bSeed
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static void generateRandomMatrix( MatrixBlock out,
			                RandomMatrixGenerator rgen, long[] nnzInBlocks, 
							Well1024a bigrand, long bSeed ) throws DMLRuntimeException
	{
		boolean invokedFromCP = true;
		if(bigrand == null && nnzInBlocks!=null)
			invokedFromCP = false;
		
		/*
		 * Setup min and max for distributions other than "uniform". Min and Max
		 * are set up in such a way that the usual logic of
		 * (max-min)*prng.nextDouble() is still valid. This is done primarily to
		 * share the same code across different distributions.
		 */
		double min=0, max=1;
		if ( rgen._pdf.equalsIgnoreCase(RAND_PDF_UNIFORM) ) {
			min=rgen._min;
			max=rgen._max;
		}
		
		int rows = rgen._rows;
		int cols = rgen._cols;
		int rpb = rgen._rowsPerBlock;
		int cpb = rgen._colsPerBlock;
		double sparsity = rgen._sparsity;
		
		// Determine the sparsity of output matrix
		// if invoked from CP: estimated NNZ is for entire matrix (nnz=0, if 0 initialized)
		// if invoked from MR: estimated NNZ is for one block
		final long estnnz = (invokedFromCP ? ((min==0.0 && max==0.0)? 0 : (long)(sparsity * rows * cols)) 
				                           : nnzInBlocks[0]);
		boolean lsparse = MatrixBlock.evalSparseFormatInMemory( rows, cols, estnnz );
		out.reset(rows, cols, lsparse);
		
		// Special case shortcuts for efficiency
		if ( rgen._pdf.equalsIgnoreCase(RAND_PDF_UNIFORM)) {
			//specific cases for efficiency
			if ( min == 0.0 && max == 0.0 ) { //all zeros
				// nothing to do here
				out.nonZeros = 0;
				return;
			} 
			else if( !out.sparse && sparsity==1.0d && (min == max  //equal values, dense
					|| (Double.isNaN(min) && Double.isNaN(max))) ) //min == max == NaN
			{
				out.init(min, out.rlen, out.clen); 
				return;
			}
		}
		
		// Allocate memory
		if ( out.sparse ) {
			//note: individual sparse rows are allocated on demand,
			//for consistency with memory estimates and prevent OOMs.
			out.allocateSparseRowsBlock();
		}
		else{
			out.allocateDenseBlock();	
		}

		int nrb = (int) Math.ceil((double)rows/rpb);

		//Timing time = new Timing(true);

		long[] seeds = null;
		if ( invokedFromCP ) {
			int numBlocks = nrb * (int)Math.ceil((double)cols/cpb);
			seeds = new long[numBlocks];
			for (int l = 0; l < numBlocks; l++ ) {
				// case of CP: generate a block-level seed from matrix-level Well1024a seed
				seeds[l] = bigrand.nextLong();
			}
		}
		computeRand(invokedFromCP, 0, nrb, out, rgen, nnzInBlocks, bSeed, seeds);
		
		//System.out.println("Rand -Seq: " + time.stop());
		out.recomputeNonZeros();
	}
	
	/**
	 * Function to generate a matrix of random numbers. This is invoked both
	 * from CP as well as from MR. In case of CP, it generates an entire matrix
	 * block-by-block. A <code>bigrand</code> is passed so that block-level
	 * seeds are generated internally. In case of MR, it generates a single
	 * block for given block-level seed <code>bSeed</code>.
	 * 
	 * When pdf="uniform", cell values are drawn from uniform distribution in
	 * range <code>[min,max]</code>.
	 * 
	 * When pdf="normal", cell values are drawn from standard normal
	 * distribution N(0,1). The range of generated values will always be
	 * (-Inf,+Inf).
	 * 
	 * @param rows
	 * @param cols
	 * @param rowsInBlock
	 * @param colsInBlock
	 * @param sparsity
	 * @param min
	 * @param max
	 * @param bigrand
	 * @param bSeed
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static void generateRandomMatrix( MatrixBlock out,
            RandomMatrixGenerator rgen, long[] nnzInBlocks, 
			Well1024a bigrand, long bSeed, int k ) throws DMLRuntimeException	{
		
		int rows = rgen._rows;
		int cols = rgen._cols;
		int rpb = rgen._rowsPerBlock;
		int cpb = rgen._colsPerBlock;
		double sparsity = rgen._sparsity;
		
		if (rows == 1) {
			generateRandomMatrix(out, rgen, nnzInBlocks, bigrand, bSeed);
			return;
		}

		boolean invokedFromCP = true;
		if(bigrand == null && nnzInBlocks!=null)
			invokedFromCP = false;

		/*
		 * Setup min and max for distributions other than "uniform". Min and Max
		 * are set up in such a way that the usual logic of
		 * (max-min)*prng.nextDouble() is still valid. This is done primarily to
		 * share the same code across different distributions.
		 */
		double min=0, max=1;
		if ( rgen._pdf.equalsIgnoreCase(RAND_PDF_UNIFORM) ) {
			min=rgen._min;
			max=rgen._max;
		}
		
		// Determine the sparsity of output matrix
		// if invoked from CP: estimated NNZ is for entire matrix (nnz=0, if 0 initialized)
		// if invoked from MR: estimated NNZ is for one block
		final long estnnz = (invokedFromCP ? ((min==0.0 && max==0.0)? 0 : (long)(sparsity * rows * cols)) 
				                           : nnzInBlocks[0]);
		boolean lsparse = MatrixBlock.evalSparseFormatInMemory( rows, cols, estnnz );
		out.reset(rows, cols, lsparse);
		
		// Special case shortcuts for efficiency
		if ( rgen._pdf.equalsIgnoreCase(RAND_PDF_UNIFORM)) {
			//specific cases for efficiency
			if ( min == 0.0 && max == 0.0 ) { //all zeros
				// nothing to do here
				out.nonZeros = 0;
				return;
			} 
			else if( !out.sparse && sparsity==1.0d && min == max ) //equal values
			{
				out.init(min, out.rlen, out.clen); 
				return;
			}
		}
		
		// Allocate memory
		if ( out.sparse ) {
			//note: individual sparse rows are allocated on demand,
			//for consistency with memory estimates and prevent OOMs.
			out.allocateSparseRowsBlock();
		}
		else{
			out.allocateDenseBlock();	
		}

		int numThreads = k;
		int nrb = (int) Math.ceil((double)rows/rpb);
		int nrb_incr = 0;
		
		if (nrb < numThreads) {
			numThreads = nrb;
		}

		//Timing time = new Timing(true);
		
		int rl = 0, ru = 0;

		ExecutorService pool = Executors.newFixedThreadPool(numThreads);
		ArrayList<computeRandTask> tasks = new ArrayList<computeRandTask>();
		
		for (int i = 0; i < numThreads; i++) {
			nrb_incr = (int) Math.ceil((double)(nrb - rl)/(numThreads - i));
			ru = rl + nrb_incr;
			if (nrb < ru) ru = nrb;
			long[] seeds = null;
			if ( invokedFromCP ) {
				int numBlocks = nrb_incr * (int)Math.ceil((double)cols/cpb);
				seeds = new long[numBlocks];
				for (int l = 0; l < numBlocks; l++ ) {
					// case of CP: generate a block-level seed from matrix-level Well1024a seed
					seeds[l] = bigrand.nextLong();
				}
			}

			computeRandTask t = new computeRandTask(invokedFromCP, rl, ru, out, rgen, nnzInBlocks, bSeed, seeds);
			tasks.add(t);
			rl = ru;
		}
		
		try {
			pool.invokeAll(tasks);
			pool.shutdown();
		} catch (Exception e) {
			throw new DMLRuntimeException("Threadpool issue, while invoking parallel RandGen.", e);
		}	
		
		//early error notify in case not all tasks successful
		for(computeRandTask rt : tasks) {
			if( !rt.getReturnCode() ) {
				throw new DMLRuntimeException("RandGen task failed: " + rt.getErrMsg());
			}
		}
		//System.out.println("Rand -Par: " + time.stop() + " with " + tasks.size() + " threads");
		out.recomputeNonZeros();
	}
	
	public static void computeRand(boolean invokedFromCP, int rl, int ru, MatrixBlock out, RandomMatrixGenerator rgen, long[] nnzInBlocks, long bSeed, long[] seeds) throws DMLRuntimeException {

		int rows = rgen._rows;
		int cols = rgen._cols;
		int rpb = rgen._rowsPerBlock;
		int cpb = rgen._colsPerBlock;
		double sparsity = rgen._sparsity;
		PRNGenerator valuePRNG = rgen._valuePRNG;
		double min=0, max=1;
		if ( rgen._pdf.equalsIgnoreCase(RAND_PDF_UNIFORM) ) {
			min=rgen._min;
			max=rgen._max;
		}
		
		double range = max - min;

		final int clen = out.clen;
		final int estimatedNNzsPerRow = out.estimatedNNzsPerRow;
		
		int nrb = (int) Math.ceil((double)rows/rpb);
		int ncb = (int) Math.ceil((double)cols/cpb);
		int blockrows, blockcols, rowoffset, coloffset;
		int blockID = rl*ncb;
		int counter = 0;

		// Setup Pseudo Random Number Generator for cell values based on 'pdf'.
		if (valuePRNG == null) {
			if ( rgen._pdf.equalsIgnoreCase(RAND_PDF_UNIFORM)) 
				valuePRNG = new UniformPRNGenerator();
			else if ( rgen._pdf.equalsIgnoreCase(RAND_PDF_NORMAL))
				valuePRNG = new NormalPRNGenerator();
			else if ( rgen._pdf.equalsIgnoreCase(RAND_PDF_POISSON))
				valuePRNG = new PoissonPRNGenerator();
			else
				throw new DMLRuntimeException("Unsupported distribution function for Rand: " + rgen._pdf);
		}
		
		// loop through row-block indices
		for(int rbi=rl; rbi < ru; rbi++) {
			blockrows = (rbi == nrb-1 ? (rows-rbi*rpb) : rpb);
			rowoffset = rbi*rpb;

			// loop through column-block indices
			for(int cbj=0; cbj < ncb; cbj++, blockID++) {
				blockcols = (cbj == ncb-1 ? (cols-cbj*cpb) : cpb);
				coloffset = cbj*cpb;
				// Generate a block (rbi,cbj) 
				
				// select the appropriate block-level seed
				long seed = -1;
				if ( !invokedFromCP ) {
					// case of MR: simply use the passed-in value
					seed = bSeed;
				}
				else {
					// case of CP: generate a block-level seed from matrix-level Well1024a seed
					seed = seeds[counter++]; //bigrand.nextLong();
				}
				// Initialize the PRNGenerator for cell values
				valuePRNG.setSeed(seed);
				
				// Initialize the PRNGenerator for determining cells that contain a non-zero value
				// Note that, "pdf" parameter applies only to cell values and the individual cells 
				// are always selected uniformly at random.
				UniformPRNGenerator nnzPRNG = new UniformPRNGenerator(seed);

				// block-level sparsity, which may differ from overall sparsity in the matrix.
				// (e.g., border blocks may fall under skinny matrix turn point, in CP this would be 
				// irrelevant but we need to ensure consistency with MR)
				boolean localSparse = MatrixBlock.evalSparseFormatInMemory(blockrows, blockcols, nnzInBlocks[blockID] ); //(long)(sparsity*blockrows*blockcols));  
				if ( localSparse ) {
					SparseRow[] c = out.sparseRows;
					
					int idx = 0;  // takes values in range [1, brlen*bclen] (both ends including)
					int ridx=0, cidx=0; // idx translates into (ridx, cidx) entry within the block
					int skip = -1;
					double p = sparsity;
			        
					// Prob [k-1 zeros before a nonzero] = Prob [k-1 < log(uniform)/log(1-p) < k] = p*(1-p)^(k-1), where p=sparsity
					double log1mp = Math.log(1-p);
					long blocksize = blockrows*blockcols;
					while(idx < blocksize) {
						skip = (int) Math.ceil( Math.log(nnzPRNG.nextDouble())/log1mp )-1;
						idx = idx+skip+1;

						if ( idx > blocksize)
							break;
						
						// translate idx into (r,c) within the block
						ridx = (idx-1)/blockcols;
						cidx = (idx-1)%blockcols;
						double val = min + (range * valuePRNG.nextDouble());
						if( c[rowoffset+ridx]==null )
							c[rowoffset+ridx]=new SparseRow(estimatedNNzsPerRow, clen);
						c[rowoffset+ridx].append(coloffset+cidx, val);
					}
				}
				else {
					if (sparsity == 1.0) {
						double[] c = out.denseBlock;
						for(int ii=0; ii < blockrows; ii++) {
							for(int jj=0, index = ((ii+rowoffset)*cols)+coloffset; jj < blockcols; jj++, index++) {
								c[index] = min + (range * valuePRNG.nextDouble());
							}
						}
					}
					else {
						if (out.sparse ) {
							/* This case evaluated only when this function is invoked from CP. 
							 * In this case:
							 *     sparse=true -> entire matrix is in sparse format and hence denseBlock=null
							 *     localSparse=false -> local block is dense, and hence on MR side a denseBlock will be allocated
							 * i.e., we need to generate data in a dense-style but set values in sparseRows
							 * 
							 */
							// In this case, entire matrix is in sparse format but the current block is dense
							SparseRow[] c = out.sparseRows;
							for(int ii=0; ii < blockrows; ii++) {
								for(int jj=0; jj < blockcols; jj++) {
									if(nnzPRNG.nextDouble() <= sparsity) {
										double val = min + (range * valuePRNG.nextDouble());
										if( c[ii+rowoffset]==null )
											c[ii+rowoffset]=new SparseRow(estimatedNNzsPerRow, clen);
										c[ii+rowoffset].append(jj+coloffset, val);
									}
								}
							}
						}
						else {
							double[] c = out.denseBlock;
							for(int ii=0; ii < blockrows; ii++) {
								for(int jj=0, index = ((ii+rowoffset)*cols)+coloffset; jj < blockcols; jj++, index++) {
									if(nnzPRNG.nextDouble() <= sparsity) {
										c[index] =  min + (range * valuePRNG.nextDouble());
									}
								}
							}
						}
					}
				} // sparse or dense 
			} // cbj
		} // rbi	
	}
	
	/**
	 * Method to generate a sequence according to the given parameters. The
	 * generated sequence is always in dense format.
	 * 
	 * Both end points specified <code>from</code> and <code>to</code> must be
	 * included in the generated sequence i.e., [from,to] both inclusive. Note
	 * that, <code>to</code> is included only if (to-from) is perfectly
	 * divisible by <code>incr</code>.
	 * 
	 * For example, seq(0,1,0.5) generates (0.0 0.5 1.0) 
	 *      whereas seq(0,1,0.6) generates (0.0 0.6) but not (0.0 0.6 1.0)
	 * 
	 * @param from
	 * @param to
	 * @param incr
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static void generateSequence(MatrixBlock out, double from, double to, double incr) 
		throws DMLRuntimeException 
	{
		boolean neg = (from > to);
		if (neg != (incr < 0))
			throw new DMLRuntimeException("Wrong sign for the increment in a call to seq(): from="+from+", to="+to+ ", incr="+incr);
		
		int rows = 1 + (int)Math.floor((to-from)/incr);
		int cols = 1;
		out.sparse = false; // sequence matrix is always dense
		out.reset(rows, cols, out.sparse);
		
		out.allocateDenseBlock();
		
		//System.out.println(System.nanoTime() + ": MatrixBlockDSM.seq(): seq("+from+","+to+","+incr+") rows = " + rows);
		double[] c = out.denseBlock; 
		
		c[0] = from;
		for(int i=1; i < rows; i++) {
			from += incr;
			c[i] = from;
		}
		
		out.recomputeNonZeros();
		//System.out.println(System.nanoTime() + ": end of seq()");
	}

	
	
	/**
	 * 
	 * 
	 */
	public static class computeRandTask implements Callable<Object> 
	{
		private boolean _rc = true;
		private String _errMsg = null;
		private boolean _invokedFromCP = true;
		private int _rl = 0;
		private int _ru = 0;
		private MatrixBlock _out = null;
		private RandomMatrixGenerator _rgen = new RandomMatrixGenerator();
		private long[] _nnzInBlocks = null;
		private long _bSeed = 0;
		private long[] _seeds = null;
		
	
		public computeRandTask(boolean invokedFromCP, int rl, int ru, MatrixBlock out, RandomMatrixGenerator rgen, long[] nnzInBlocks, long bSeed, long[] seeds) throws DMLRuntimeException 
		{
			_invokedFromCP = invokedFromCP;
			_rl = rl;
			_ru = ru;
			_out = out;
			_rgen.init(rgen._pdf, rgen._rows, rgen._cols, rgen._rowsPerBlock, rgen._colsPerBlock, rgen._sparsity, rgen._min, rgen._max, rgen._mean);
			_nnzInBlocks = nnzInBlocks;
			_bSeed = bSeed;
			_seeds = seeds;
		}
		
		public boolean getReturnCode() {
			return _rc;
		}
		
		public String getErrMsg() {
			return _errMsg;
		}

		@Override		
		public Object call() throws Exception
		{
			computeRand(_invokedFromCP, _rl, _ru, _out, _rgen, _nnzInBlocks, _bSeed, _seeds);
			return null;
		}

	}
	
	/**
     * Generates a sample of size <code>size</code> from a range of values [1,range].
     * <code>replace</code> defines if sampling is done with or without replacement.
     * 
     * @param ec
     * @return
     * @throws DMLRuntimeException
     */
	public static void generateSample(MatrixBlock out, long range, int size, boolean replace, long seed)
		throws DMLRuntimeException 
	{
		//set meta data and allocate dense block
		out.reset(size, 1, false);
		out.allocateDenseBlock();
		seed = (seed == -1 ? System.nanoTime() : seed);
		
		if ( !replace ) 
		{
			// reservoir sampling
			
			for(int i=1; i <= size; i++) 
				out.setValueDenseUnsafe(i-1, 0, i );
			
			Random rand = new Random(seed);
			for(int i=size+1; i <= range; i++) 
			{
				if(rand.nextInt(i) < size)
					out.setValueDenseUnsafe( rand.nextInt(size), 0, i );
			}
			
			// randomize the sample (Algorithm P from Knuth's ACP)
			// -- needed especially when the differnce between range and size is small)
			double tmp;
			int idx;
			for(int i=size-1; i >= 1; i--) 
			{
				idx = rand.nextInt(i);
				// swap i^th and idx^th entries
				tmp = out.getValueDenseUnsafe(idx, 0);
				out.setValueDenseUnsafe(idx, 0, out.getValueDenseUnsafe(i, 0));
				out.setValueDenseUnsafe(i, 0, tmp);
			}
		}
		else 
		{
			Random r = new Random(seed);
			for(int i=0; i < size; i++) 
				out.setValueDenseUnsafe(i, 0, 1+nextLong(r, range) );
		}
		
		out.recomputeNonZeros();
		out.examSparsity();
	}

	// modified version of java.util.nextInt
    private static long nextLong(Random r, long n) {
        if (n <= 0)
            throw new IllegalArgumentException("n must be positive");

        //if ((n & -n) == n)  // i.e., n is a power of 2
        //    return ((n * (long)r.nextLong()) >> 31);

        long bits, val;
        do {
            bits = (r.nextLong() << 1) >>> 1;
            val = bits % n;
        } while (bits - val + (n-1) < 0L);
        return val;
    }
    
    public static RandomMatrixGenerator createRandomMatrixGenerator(String pdf, int r, int c, int rpb, int cpb, double sp, double min, double max, String distParams) throws DMLRuntimeException
    {
    	RandomMatrixGenerator rgen = null;
    	
    	if ( pdf.equalsIgnoreCase(RAND_PDF_UNIFORM))
    		rgen = new RandomMatrixGenerator(pdf, r, c, rpb, cpb, sp, min, max);
    	else if ( pdf.equalsIgnoreCase(RAND_PDF_NORMAL))
    		rgen = new RandomMatrixGenerator(pdf, r, c, rpb, cpb, sp);
    	else if ( pdf.equalsIgnoreCase(RAND_PDF_POISSON))
    	{
    		double mean = Double.NaN;
    		try {
    			mean = Double.parseDouble(distParams);
    		} catch(NumberFormatException e) {
    			throw new DMLRuntimeException("Failed to parse Poisson distribution parameter: " + distParams);
    		}
    		rgen = new RandomMatrixGenerator(pdf, r, c, rpb, cpb, sp, min, max, mean);
    	}
    	else
    		throw new DMLRuntimeException("Unsupported probability distribution \"" + pdf + "\" in rand() -- it must be one of \"uniform\", \"normal\", or \"poisson\"");
    	return rgen;
    }
}
