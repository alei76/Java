package model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import duyuNN.AverageLayer;
import duyuNN.LinearLayer;
import duyuNN.LookupLayer;
import duyuNN.MultiConnectLayer;
import duyuNN.NNInterface;
import duyuNN.SoftmaxLayer;
import duyuNN.TanhLayer;
import duyuNN.combinedLayer.AttentionLayer;
import duyuNN.combinedLayer.ConnectLinearTanh;
import duyuNN.combinedLayer.ConnectLinearTanhLinear;
import duyuNN.combinedLayer.LookupLinearTanh;
import duyuNN.combinedLayer.SimplifiedLSTMLayer;

//we exactly use the strategy in end-to-end memory network.
//for the j-th word in k-th hop, the position vector 
//l_{kj} = (1-j/J) - (k/d) * (1-2 * j/J), 1-based indexing
//J is the number of words, d is dimension of word embedding

public class SentenceEntityMemNN_Position_1 implements NNInterface{
	
	List<LookupLayer> lookupList;
	MultiConnectLayer lookupConnect;
	
	int hiddenLength;
	int linkId;
	
	double[] targetVec;
	
	public double[][] outputs;
	public double[][] outputGs;

	NNInterface[] entityTransforms;
	AttentionLayer[] attentionLayers;
	
	int numberOfHops;
	
	double[][] positionVecs;
	
	public SentenceEntityMemNN_Position_1()
	{
		
	}
	
	public SentenceEntityMemNN_Position_1(
			int[] wordIds,
			int[] wordPositions,
			LookupLayer seedLookup,
			NNInterface seedAttentionCell,
 			NNInterface seedEntityTransform,
			double[] xTargetVec,
			int xnumberOfHops
		) throws Exception 
	{
		numberOfHops = xnumberOfHops;
		
		targetVec = new double[xTargetVec.length];
		System.arraycopy(xTargetVec, 0, targetVec, 0, xTargetVec.length);
		
		hiddenLength = seedLookup.embeddingLength;
		lookupList = new ArrayList<LookupLayer>();
		
		int[] lookupConnectLengths = new int[wordIds.length];
		Arrays.fill(lookupConnectLengths, hiddenLength);
		lookupConnect = new MultiConnectLayer(lookupConnectLengths);
		
		for(int i = 0; i < wordIds.length; i++)
		{
			LookupLayer tmpLookup = (LookupLayer) seedLookup.cloneWithTiedParams();
			tmpLookup.input[0] = wordIds[i];
			tmpLookup.link(lookupConnect, i);
			
			lookupList.add(tmpLookup);
		}
		
		attentionLayers = new AttentionLayer[numberOfHops];
		for(int i = 0; i < numberOfHops; i++)
		{
			attentionLayers[i] = new AttentionLayer(seedAttentionCell, xTargetVec.length, wordIds.length);
		}
		
		entityTransforms = new NNInterface[numberOfHops]; 
		for(int i = 0; i < numberOfHops; i++)
		{
			entityTransforms[i] = (NNInterface) seedEntityTransform.cloneWithTiedParams();
		}
		
		outputs = new double[numberOfHops][];
		outputGs = new double[numberOfHops][];
		
		for(int i = 0; i < numberOfHops; i++)
		{
			outputs[i] = new double[hiddenLength];
			outputGs[i] = new double[hiddenLength];
		}
		
		positionVecs = new double[numberOfHops][];
		for(int k = 0; k < numberOfHops; k++)
		{
			positionVecs[k] = new double[wordIds.length];
			for(int j = 0; j < wordIds.length; j++)
			{
				int position = wordPositions[j];
				//l_{kj} = (1-j/J) - (k/d) * (1-2 * j/J)
				positionVecs[k][j] = 1.0 - 1.0 * position / wordIds.length
							- 1.0 * k * (1.0 - 2.0 * position / wordIds.length) / seedLookup.embeddingLength;
			}
		}
		
		linkId = 0;
	}
	
	@Override
	public void randomize(Random r, double min, double max) {
		
	}

	@Override
	public void forward() {
		for(int i = 0; i < lookupList.size(); i++)
		{
			lookupList.get(i).forward();
		}
		
		lookupConnect.forward();
		
		// i should start from 0 and end until numberOfHops - 1
		for(int i = 0; i < numberOfHops; i++)
		{
			double[] positionedLookupConnect = new double[lookupConnect.outputLength];
			for(int j = 0; j < lookupConnect.outputLength; j++)
			{
				positionedLookupConnect[j] = lookupConnect.output[j] * positionVecs[i][j / hiddenLength];
			}
			
			if(i == 0)
			{
				attentionLayers[i].forward(targetVec, positionedLookupConnect);
				System.arraycopy(targetVec, 0, 
						(double[]) entityTransforms[i].getInput(0), 0, targetVec.length);
			}
			else
			{
				attentionLayers[i].forward(outputs[i-1], positionedLookupConnect);
				System.arraycopy(outputs[i-1], 0, 
						(double[]) entityTransforms[i].getInput(0), 0, outputs[i-1].length);
			}
			
			entityTransforms[i].forward();
			
			for(int j = 0; j < outputs[i].length; j++)
			{
				outputs[i][j] = ((double[]) entityTransforms[i].getOutput(0))[j] 
						+ attentionLayers[i].output[j];
			}
		}
	}

	@Override
	public void backward() {
		
		// i should start from numberOfHops - 1 and end until 0
		for(int i = numberOfHops - 1; i >=0 ; i--)
		{
			for(int j = 0; j < outputs[i].length; j++)
			{
				attentionLayers[i].outputG[j] += outputGs[i][j];
				((double[]) entityTransforms[i].getOutputG(0) )[j] += outputGs[i][j];
			}
			
			entityTransforms[i].backward();
			attentionLayers[i].backward();
			
			for(int j = 0; j < attentionLayers[i].contextGs.length; j++)
			{
				lookupConnect.outputG[j] += attentionLayers[i].contextGs[j] * positionVecs[i][j / hiddenLength];
			}
			
			if(i > 0)
			{
				for(int j = 0; j < outputGs[i-1].length; j++)
				{
					outputGs[i-1][j] += attentionLayers[i].inputG[j]
							+ ( (double[]) entityTransforms[i].getInputG(0) )[j];
				}
			}
		}
		
//		lookupConnect.backward();
//		
//		for(int j = 0; j < lookupList.size(); j++)
//		{
//			lookupList.get(j).backward();
//		}
	}

	@Override
	public void update(double learningRate) {
		
		for(int i = 0; i < numberOfHops; i++)
		{
			attentionLayers[i].update(learningRate);
			entityTransforms[i].update(learningRate);
		}
	}
	
	@Override
	public void updateAdaGrad(double learningRate, int batchsize) {
		for(int i = 0; i < numberOfHops; i++)
		{
			attentionLayers[i].updateAdaGrad(learningRate, batchsize);
			entityTransforms[i].updateAdaGrad(learningRate, batchsize);
		}
	}

	@Override
	public void clearGrad() {
		
		for(int i = lookupList.size() - 1; i >= 0 ; i--)
		{
			lookupList.get(i).clearGrad();
		}
		lookupList.clear();
		
		lookupConnect.clearGrad();
		
		for(int i = 0; i < numberOfHops; i++)
		{
			attentionLayers[i].clearGrad();
			entityTransforms[i].clearGrad();
			
			Arrays.fill(outputs[i], 0);
			Arrays.fill(outputGs[i], 0);
		}
	}

	@Override
	public void link(NNInterface nextLayer, int id) throws Exception {
		Object nextInputG = nextLayer.getInputG(id);
		Object nextInput = nextLayer.getInput(id);
		
		double[] nextI = (double[])nextInput;
		double[] nextIG = (double[])nextInputG; 
		
		if(nextI.length != outputs[numberOfHops - 1].length 
				|| nextIG.length != outputGs[numberOfHops - 1].length)
		{
			throw new Exception("The Lengths of linked layers do not match.");
		}
		outputs[numberOfHops - 1] = nextI;
		outputGs[numberOfHops - 1] = nextIG;
	}

	@Override
	public void link(NNInterface nextLayer) throws Exception {
		// TODO Auto-generated method stub
		link(nextLayer, linkId);
	}

	@Override
	public Object getInput(int id) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object getOutput(int id) {
		// TODO Auto-generated method stub
		return outputs[numberOfHops - 1];
	}

	@Override
	public Object getInputG(int id) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Object getOutputG(int id) {
		// TODO Auto-generated method stub
		return outputGs[numberOfHops - 1];
	}

	@Override
	public Object cloneWithTiedParams() {
		return null;
	}
}
