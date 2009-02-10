/**
 * FixedLengthIterativePairwiseAligner.java
 */
package edu.ohsu.cslu.alignment.multiple;

import edu.ohsu.cslu.alignment.AlignmentModel;
import edu.ohsu.cslu.alignment.pairwise.FixedLengthDynamicAligner;
import edu.ohsu.cslu.alignment.pairwise.SequenceAlignment;
import edu.ohsu.cslu.common.MappedSequence;
import edu.ohsu.cslu.math.linear.Matrix;


public class FixedLengthIterativePairwiseAligner extends BaseMultipleSequenceAligner
{
    public FixedLengthIterativePairwiseAligner()
    {
        aligner = new FixedLengthDynamicAligner();
    }

    /**
     * Assumes the longer of the two sequences has already been aligned into the aligned set.
     * 
     * @param pair
     * @return
     */
    @Override
    protected SequenceAlignment align(final MappedSequence alignedSequence, final MappedSequence unalignedSequence,
        final AlignmentModel alignmentModel)
    {
        return aligner.alignPair(unalignedSequence, alignedSequence, alignmentModel);
    }

    @Override
    protected int firstSequenceToAlign(MappedSequence[] sequences, Matrix distanceMatrix)
    {
        // Start aligning with the longest sequence
        // We could (alternatively) pad one of the two closest sequences and insert it first,
        // but starting with the longest provides better performance.

        int longestSequenceIndex = 0;
        int maxLength = 0;
        for (int i = 1; i < sequences.length; i++)
        {
            if (sequences[i].length() > maxLength)
            {
                longestSequenceIndex = i;
                maxLength = sequences[i].length();
            }
        }
        return longestSequenceIndex;
    }
}