package org.seqdoop.hadoop_bam;

// Copyright (c) 2010 Aalto University
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to
// deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
// FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
// IN THE SOFTWARE.

// File created: 2010-08-09 14:34:08


import htsjdk.samtools.BAMFileReader;
import htsjdk.samtools.BAMFileSpan;
import htsjdk.samtools.Chunk;
import htsjdk.samtools.QueryInterval;
import htsjdk.samtools.SamFiles;
import htsjdk.samtools.SamInputResource;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.Interval;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMRecord;

import org.seqdoop.hadoop_bam.BAMInputFormat;
import org.seqdoop.hadoop_bam.FileVirtualSplit;
import org.seqdoop.hadoop_bam.SAMRecordWritable;
import org.seqdoop.hadoop_bam.util.MurmurHash3;
import org.seqdoop.hadoop_bam.util.NIOFileUtil;
import org.seqdoop.hadoop_bam.util.SAMHeaderReader;
import org.seqdoop.hadoop_bam.util.WrapSeekable;

import htsjdk.samtools.util.zip.InflaterFactory;
import com.intel.gkl.compression.*;

import static org.seqdoop.hadoop_bam.BAMBDGInputFormat.INFLATE_FACTORY;

/** The key is the bitwise OR of the reference sequence ID in the upper 32 bits
 * and the 0-based leftmost coordinate in the lower.
 */
public class BAMBDGRecordReader
        extends RecordReader<LongWritable,SAMRecordWritable>
{
    private final LongWritable key = new LongWritable();
    private final SAMRecordWritable record = new SAMRecordWritable();

    private CloseableIterator<SAMRecord> iterator;
    private boolean reachedEnd;
    private WrapSeekable<FSDataInputStream> in;
    private long fileStart;
    private long virtualEnd;
    private boolean isInitialized = false;

    /** Note: this is the only getKey function that handles unmapped reads
     * specially!
     */
    public static long getKey(final SAMRecord rec) {
        final int refIdx = rec.getReferenceIndex();
        final int start  = rec.getAlignmentStart();

        if (!(rec.getReadUnmappedFlag() || refIdx < 0 || start < 0))
            return getKey(refIdx, start);

        // Put unmapped reads at the end, but don't give them all the exact same
        // key so that they can be distributed to different reducers.
        //
        // A random number would probably be best, but to ensure that the same
        // record always gets the same key we use a fast hash instead.
        //
        // We avoid using hashCode(), because it's not guaranteed to have the
        // same value across different processes.

        int hash = 0;
        byte[] var;
        if ((var = rec.getVariableBinaryRepresentation()) != null) {
            // Undecoded BAM record: just hash its raw data.
            hash = (int)MurmurHash3.murmurhash3(var, hash);
        } else {
            // Decoded BAM record or any SAM record: hash a few representative
            // fields together.
            hash = (int)MurmurHash3.murmurhash3(rec.getReadName(), hash);
            hash = (int)MurmurHash3.murmurhash3(rec.getReadBases(), hash);
            hash = (int)MurmurHash3.murmurhash3(rec.getBaseQualities(), hash);
            hash = (int)MurmurHash3.murmurhash3(rec.getCigarString(), hash);
        }
        return getKey0(Integer.MAX_VALUE, hash);
    }

    /** @param alignmentStart 1-based leftmost coordinate. */
    public static long getKey(int refIdx, int alignmentStart) {
        return getKey0(refIdx, alignmentStart-1);
    }

    /** @param alignmentStart0 0-based leftmost coordinate. */
    public static long getKey0(int refIdx, int alignmentStart0) {
        return (long)refIdx << 32 | alignmentStart0;
    }

    @Override public void initialize(InputSplit spl, TaskAttemptContext ctx)
            throws IOException
    {
        // This method should only be called once (see Hadoop API). However,
        // there seems to be disagreement between implementations that call
        // initialize() and Hadoop-BAM's own code that relies on
        // {@link BAMInputFormat} to call initialize() when the reader is
        // created. Therefore we add this check for the time being.
        if(isInitialized) {
            if(in != null) in.close();
            close();
        }
        isInitialized = true;
        reachedEnd = false;

        final Configuration conf = ctx.getConfiguration();

        final FileVirtualSplit split = (FileVirtualSplit)spl;
        final Path             file  = split.getPath();
        final FileSystem       fs    = file.getFileSystem(conf);

        ValidationStringency stringency = SAMHeaderReader.getValidationStringency(conf);

        java.nio.file.Path index = SamFiles.findIndex(NIOFileUtil.asPath(fs.makeQualified(file).toUri()));
        Path fileIndex = index == null ? null : new Path(index.toUri());
        if( fileIndex != null && fileIndex.toString().startsWith("hdfs")){
            fileIndex = new Path("hdfs://" + fileIndex.toUri().getPath());
        }
        SeekableStream indexStream = fileIndex == null ? null : WrapSeekable.openPath(fs, fileIndex);
        in = WrapSeekable.openPath(fs, file);
        SamReader samReader = createSamReader(in, indexStream, stringency, conf);
        final SAMFileHeader header = samReader.getFileHeader();

        long virtualStart = split.getStartVirtualOffset();

        fileStart  = virtualStart >>> 16;
        virtualEnd = split.getEndVirtualOffset();

        SamReader.PrimitiveSamReader primitiveSamReader =
                ((SamReader.PrimitiveSamReaderToSamReaderAdapter) samReader).underlyingReader();
        BAMFileReader bamFileReader = (BAMFileReader) primitiveSamReader;

        if(org.seqdoop.hadoop_bam.BAMBDGInputFormat.DEBUG_BAM_SPLITTER) {
            final long recordStart = virtualStart & 0xffff;
            System.err.println("XXX inizialized BAMRecordReader byte offset: " +
                    fileStart + " record offset: " + recordStart);
        }

        if (conf.getBoolean("hadoopbam.bam.keep-paired-reads-together", false)) {
            throw new IllegalArgumentException("Property hadoopbam.bam.keep-paired-reads-together is no longer honored.");
        }

        List<Interval> intervals = BAMInputFormat.getIntervals(conf);
        if (intervals != null) {
            QueryInterval[] queryIntervals = BAMInputFormat.prepareQueryIntervals(intervals, header.getSequenceDictionary());
            iterator = bamFileReader.createIndexIterator(queryIntervals, false, split.getIntervalFilePointers());
        } else {
            BAMFileSpan splitSpan = new BAMFileSpan(new Chunk(virtualStart, virtualEnd));
            iterator = bamFileReader.getIterator(splitSpan);
        }
    }

    private SamReader createSamReader(SeekableStream in, SeekableStream inIndex,
                                      ValidationStringency stringency, Configuration conf) {
        SamReaderFactory readerFactory = SamReaderFactory.makeDefault()
                .setOption(SamReaderFactory.Option.CACHE_FILE_BASED_INDEXES, true)
                .setOption(SamReaderFactory.Option.EAGERLY_DECODE, false)
                .setOption(SamReaderFactory.Option.VALIDATE_CRC_CHECKSUMS, false)
                .setUseAsyncIo(false);
        if (stringency != null) {
            readerFactory.validationStringency(stringency);
        }
        SamInputResource resource = SamInputResource.of(in);
        if (inIndex != null) {
            resource.index(inIndex);
        }
        if(conf.get(INFLATE_FACTORY) != null && conf.get(INFLATE_FACTORY).equalsIgnoreCase("intel_gkl")){
            IntelInflaterFactory intelDeflaterFactory =  new IntelInflaterFactory();
            readerFactory.inflaterFactory(intelDeflaterFactory);
        }

        return readerFactory.open(resource);
    }

    @Override public void close() throws IOException { iterator.close(); }

    /** Unless the end has been reached, this only takes file position into
     * account, not the position within the block.
     */
    @Override public float getProgress() throws IOException {
        if (reachedEnd)
            return 1;
        else {
            final long filePos = in.position();
            final long fileEnd = virtualEnd >>> 16;
            // Add 1 to the denominator to make sure it doesn't reach 1 here when
            // filePos == fileEnd.
            return (float)(filePos - fileStart) / (fileEnd - fileStart + 1);
        }
    }
    @Override public LongWritable      getCurrentKey  () { return key; }
    @Override public SAMRecordWritable getCurrentValue() { return record; }

    @Override public boolean nextKeyValue() {
        if (!iterator.hasNext()) {
            reachedEnd = true;
            return false;
        }
        final SAMRecord r = iterator.next();
        key.set(getKey(r));
        record.set(r);
        return true;
    }
}
