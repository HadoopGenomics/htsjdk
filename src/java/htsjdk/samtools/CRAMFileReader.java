/*******************************************************************************
 * Copyright 2013 EMBL-EBI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package htsjdk.samtools;

import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.SamReader.Type;
import htsjdk.samtools.cram.build.CramIO;
import htsjdk.samtools.cram.ref.ReferenceSource;
import htsjdk.samtools.cram.structure.Container;
import htsjdk.samtools.cram.structure.CramHeader;
import htsjdk.samtools.seekablestream.SeekableFileStream;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.RuntimeEOFException;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * {@link htsjdk.samtools.BAMFileReader BAMFileReader} analogue for CRAM files.
 * Supports random access using BAI or CRAI index file formats.
 *
 * @author vadim
 */
public class CRAMFileReader extends SAMFileReader.ReaderImplementation {
    private File file;
    private final ReferenceSource referenceSource;
    private InputStream is;
    private CRAMIterator it;
    private BAMIndex mIndex;
    private File mIndexFile;
    private boolean mEnableIndexCaching;
    private File mIndexStream;
    private boolean mEnableIndexMemoryMapping;

    private ValidationStringency validationStringency;

    /**
     * Open CRAM data for reading using either the file or the input stream
     * supplied in the arguments. The
     * {@link htsjdk.samtools.Defaults#REFERENCE_FASTA default} reference fasta
     * file will be used.
     *
     * @param file CRAM file to open
     * @param is   CRAM stream to read
     */
    public CRAMFileReader(final File file, final InputStream is) {
        this(file, is, new ReferenceSource(Defaults.REFERENCE_FASTA));
    }

    /**
     * Open CRAM data for reading using either the file or the input stream
     * supplied in the arguments.
     *
     * @param cramFile        CRAM file to read
     * @param indexFile       index file to be used for random access
     * @param referenceSource a {@link htsjdk.samtools.cram.ref.ReferenceSource source} of
     *                        reference sequences
     */
    public CRAMFileReader(final File file, final InputStream is,
                          final ReferenceSource referenceSource) {
        if (file == null && is == null)
            throw new NullPointerException(
                    "Either file or input stream is required.");

        this.file = file;
        this.is = is;
        this.referenceSource = referenceSource;

        if (file == null)
            getIterator();
    }

    /**
     * Open CRAM file for reading. If index file is supplied than random access
     * will be available.
     *
     * @param cramFile        CRAM file to read
     * @param indexFile       index file to be used for random access
     * @param referenceSource a {@link htsjdk.samtools.cram.ref.ReferenceSource source} of
     *                        reference sequences
     */
    public CRAMFileReader(final File cramFile, final File indexFile,
                          final ReferenceSource referenceSource) {
        if (file == null)
            throw new NullPointerException("File is required.");

        this.file = cramFile;
        this.mIndexFile = indexFile;
        this.referenceSource = referenceSource;

        if (file == null)
            getIterator();
    }

    public SAMRecordIterator iterator() {
        return getIterator();
    }

    @Override
    void enableIndexCaching(final boolean enabled) {
        // inapplicable to CRAM: do nothing
    }

    @Override
    void enableIndexMemoryMapping(final boolean enabled) {
        // inapplicable to CRAM: do nothing
    }

    @Override
    void enableCrcChecking(final boolean enabled) {
        // inapplicable to CRAM: do nothing
    }

    @Override
    void setSAMRecordFactory(final SAMRecordFactory factory) {
    }

    @Override
    public boolean hasIndex() {
        return mIndex != null || mIndexFile != null || mIndexStream != null;
    }

    @Override
    public BAMIndex getIndex() {
        if (!hasIndex())
            throw new SAMException("No index is available for this BAM file.");
        if (mIndex == null) {
            final SAMSequenceDictionary dictionary = getFileHeader()
                    .getSequenceDictionary();
            if (mIndexFile != null)
                mIndex = mEnableIndexCaching ? new CachingBAMFileIndex(
                        mIndexFile, dictionary, mEnableIndexMemoryMapping)
                        : new DiskBasedBAMFileIndex(mIndexFile, dictionary,
                        mEnableIndexMemoryMapping);
            else
                mIndex = mEnableIndexCaching ? new CachingBAMFileIndex(
                        mIndexStream, dictionary) : new DiskBasedBAMFileIndex(
                        mIndexStream, dictionary);
        }
        return mIndex;
    }

    @Override
    public SAMFileHeader getFileHeader() {
        return ((CRAMIterator) getIterator()).getSAMFileHeader();
    }

    @Override
    public SAMRecordIterator getIterator() {
        if (it != null && file == null)
            return it;
        try {
            CRAMIterator si = null;
            if (file != null)
                si = new CRAMIterator(new FileInputStream(file),
                        referenceSource);
            else
                si = new CRAMIterator(is, referenceSource);

            si.setValidationStringency(validationStringency);
            it = si;
            return it;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CloseableIterator<SAMRecord> getIterator(final SAMFileSpan fileSpan) {
        throw new RuntimeException("Not implemented.");
    }

    @Override
    public SAMFileSpan getFilePointerSpanningReads() {
        throw new RuntimeException("Not implemented.");
    }

    private static final SAMRecordIterator emptyIterator = new SAMRecordIterator() {

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public SAMRecord next() {
            throw new RuntimeException("No records.");
        }

        @Override
        public void remove() {
            throw new RuntimeException("Remove not supported.");
        }

        @Override
        public void close() {
        }

        @Override
        public SAMRecordIterator assertSorted(final SortOrder sortOrder) {
            return this;
        }
    };

    @Override
    public CloseableIterator<SAMRecord> queryAlignmentStart(final String sequence,
                                                            final int start) {
        long[] filePointers = null;

        // Hit the index to determine the chunk boundaries for the required
        // data.
        final SAMFileHeader fileHeader = getFileHeader();
        final int referenceIndex = fileHeader.getSequenceIndex(sequence);
        if (referenceIndex != -1) {
            final BAMIndex fileIndex = getIndex();
            final BAMFileSpan fileSpan = fileIndex.getSpanOverlapping(
                    referenceIndex, start, -1);
            filePointers = fileSpan != null ? fileSpan.toCoordinateArray()
                    : null;
        }

        if (filePointers == null || filePointers.length == 0)
            return emptyIterator;

        final SeekableStream s = getSeekableStreamOrFailWithRTE();
        CRAMIterator si = null;
        try {
            s.seek(0);
            si = new CRAMIterator(s, referenceSource);
            si.setValidationStringency(validationStringency);
            it = si;
        } catch (final IOException e) {
            throw new RuntimeEOFException(e);
        }

        Container c = null;
        for (int i = 0; i < filePointers.length; i += 2) {
            final long containerOffset = filePointers[i] >>> 16;
            final int sliceIndex = (int) ((filePointers[i] << 48) >>> 48);
            try {
                s.seek(containerOffset);
                // the following is not optimal because this is container-level
                // access, not slice:

                // CountingInputStream cis = new CountingInputStream(s) ;
                c = CramIO.readContainerHeader(s);
                // long headerSize = cis.getCount() ;
                // int sliceOffset = c.landmarks[sliceIndex] ;
                if (c.alignmentStart + c.alignmentSpan > start) {
                    s.seek(containerOffset);
                    // s.seek(containerOffset + headerSize + sliceOffset);
                    return si;
                }
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        it = null;
        return it;
    }

    @Override
    public CloseableIterator<SAMRecord> queryUnmapped() {
        final long startOfLastLinearBin = getIndex().getStartOfLastLinearBin();

        final SeekableStream s = getSeekableStreamOrFailWithRTE();
        CRAMIterator si = null;
        try {
            s.seek(0);
            si = new CRAMIterator(s, referenceSource);
            si.setValidationStringency(validationStringency);
            s.seek(startOfLastLinearBin);
            it = si;
        } catch (final IOException e) {
            throw new RuntimeEOFException(e);
        }

        return it;
    }

    private SeekableStream getSeekableStreamOrFailWithRTE() {
        SeekableStream s = null;
        if (file != null) {
            try {
                s = new SeekableFileStream(file);
            } catch (final FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        } else if (is instanceof SeekableStream)
            s = (SeekableStream) is;
        return s;
    }

    @Override
    public void close() {
        if (it != null)
            it.close();
        if (is != null)
            try {
                is.close();
            } catch (final IOException e) {
            }

        if (mIndex != null)
            mIndex.close();
    }

    @Override
    void setValidationStringency(final ValidationStringency validationStringency) {
        this.validationStringency = validationStringency;
    }

    @Override
    public ValidationStringency getValidationStringency() {
        return validationStringency;
    }

    public static boolean isCRAMFile(final File file) throws IOException {
        final int buffSize = CramHeader.magick.length;
        final FileInputStream fis = new FileInputStream(file);
        final DataInputStream dis = new DataInputStream(fis);
        final byte[] buffer = new byte[buffSize];
        dis.readFully(buffer);
        dis.close();

        return Arrays.equals(buffer, CramHeader.magick);
    }

    @Override
    public CloseableIterator<SAMRecord> query(final QueryInterval[] intervals,
                                              final boolean contained) {
        if (is == null) {
            throw new IllegalStateException("File reader is closed");
        }
        if (it != null) {
            throw new IllegalStateException("Iteration in progress");
        }
        if (mIndex == null && mIndexFile == null) {
            throw new UnsupportedOperationException(
                    "Cannot query stream-based BAM file");
        }
        throw new SAMException("Multiple interval queries not implemented.");
    }

    @Override
    public Type type() {
        return Type.CRAM_TYPE;
    }

    @Override
    void enableFileSource(final SamReader reader, final boolean enabled) {
        if (it != null)
            it.setFileSource(enabled ? reader : null);
    }
}
