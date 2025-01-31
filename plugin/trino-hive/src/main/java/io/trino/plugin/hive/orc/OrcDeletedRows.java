/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.hive.orc;

import com.google.common.collect.ImmutableSet;
import io.trino.memory.context.AggregatedMemoryContext;
import io.trino.memory.context.LocalMemoryContext;
import io.trino.orc.OrcCorruptionException;
import io.trino.plugin.hive.AcidInfo;
import io.trino.plugin.hive.HdfsEnvironment;
import io.trino.spi.Page;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.block.DictionaryBlock;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.EmptyPageSource;
import io.trino.spi.security.ConnectorIdentity;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hadoop.hive.ql.io.BucketCodec;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Verify.verify;
import static io.airlift.slice.SizeOf.sizeOfObjectArray;
import static io.trino.plugin.hive.BackgroundHiveSplitLoader.hasAttemptId;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_BAD_DATA;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_CURSOR_ERROR;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

@NotThreadSafe
public class OrcDeletedRows
{
    private static final int ORIGINAL_TRANSACTION_INDEX = 0;
    private static final int BUCKET_ID_INDEX = 1;
    private static final int ROW_ID_INDEX = 2;

    private final String sourceFileName;
    private final OrcDeleteDeltaPageSourceFactory pageSourceFactory;
    private final ConnectorIdentity identity;
    private final Configuration configuration;
    private final HdfsEnvironment hdfsEnvironment;
    private final AcidInfo acidInfo;
    private final OptionalInt bucketNumber;
    private final LocalMemoryContext systemMemoryUsage;

    @Nullable
    private Set<RowId> deletedRows;

    public OrcDeletedRows(
            String sourceFileName,
            OrcDeleteDeltaPageSourceFactory pageSourceFactory,
            ConnectorIdentity identity,
            Configuration configuration,
            HdfsEnvironment hdfsEnvironment,
            AcidInfo acidInfo,
            OptionalInt bucketNumber,
            AggregatedMemoryContext systemMemoryContext)
    {
        this.sourceFileName = requireNonNull(sourceFileName, "sourceFileName is null");
        this.pageSourceFactory = requireNonNull(pageSourceFactory, "pageSourceFactory is null");
        this.identity = requireNonNull(identity, "identity is null");
        this.configuration = requireNonNull(configuration, "configuration is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.acidInfo = requireNonNull(acidInfo, "acidInfo is null");
        this.bucketNumber = requireNonNull(bucketNumber, "bucketNumber is null");
        this.systemMemoryUsage = requireNonNull(systemMemoryContext, "systemMemoryContext is null").newLocalMemoryContext(OrcDeletedRows.class.getSimpleName());
    }

    public MaskDeletedRowsFunction getMaskDeletedRowsFunction(Page sourcePage, OptionalLong startRowId)
    {
        return new MaskDeletedRows(sourcePage, startRowId);
    }

    public interface MaskDeletedRowsFunction
    {
        /**
         * Retained position count
         */
        int getPositionCount();

        Block apply(Block block);

        static MaskDeletedRowsFunction noMaskForPage(Page page)
        {
            return new MaskDeletedRowsFunction()
            {
                int positionCount = page.getPositionCount();

                @Override
                public int getPositionCount()
                {
                    return positionCount;
                }

                @Override
                public Block apply(Block block)
                {
                    return block;
                }
            };
        }
    }

    @NotThreadSafe
    private class MaskDeletedRows
            implements MaskDeletedRowsFunction
    {
        @Nullable
        private Page sourcePage;
        private int positionCount;
        @Nullable
        private int[] validPositions;
        private final OptionalLong startRowId;

        public MaskDeletedRows(Page sourcePage, OptionalLong startRowId)
        {
            this.sourcePage = requireNonNull(sourcePage, "sourcePage is null");
            this.startRowId = requireNonNull(startRowId, "startRowId is null");
        }

        @Override
        public int getPositionCount()
        {
            if (sourcePage != null) {
                loadValidPositions();
                verify(sourcePage == null);
            }

            return positionCount;
        }

        @Override
        public Block apply(Block block)
        {
            if (sourcePage != null) {
                loadValidPositions();
                verify(sourcePage == null);
            }

            if (positionCount == block.getPositionCount()) {
                return block;
            }
            return new DictionaryBlock(positionCount, block, validPositions);
        }

        private void loadValidPositions()
        {
            verify(sourcePage != null, "sourcePage is null");
            Set<RowId> deletedRows = getDeletedRows();
            if (deletedRows.isEmpty()) {
                this.positionCount = sourcePage.getPositionCount();
                this.sourcePage = null;
                return;
            }

            int[] validPositions = new int[sourcePage.getPositionCount()];
            int validPositionsIndex = 0;
            for (int position = 0; position < sourcePage.getPositionCount(); position++) {
                RowId rowId = getRowId(position);
                if (!deletedRows.contains(rowId)) {
                    validPositions[validPositionsIndex] = position;
                    validPositionsIndex++;
                }
            }
            this.positionCount = validPositionsIndex;
            this.validPositions = validPositions;
            this.sourcePage = null;
        }

        private RowId getRowId(int position)
        {
            long originalTransaction;
            long row;
            int bucket;
            int statementId;
            if (startRowId.isPresent()) {
                // original transaction ID is always 0 for original file row delete delta.
                originalTransaction = 0;
                // For original files set the bucket number to original bucket number if table was bucketed or to 0 if it was not.
                // Set statement Id to 0.
                // Verified manually that this is consistent with Hive 3.1 behavior.
                bucket = bucketNumber.orElse(0);
                statementId = 0;
                // In case of original files, calculate row ID is start row ID of the page + current position in the page
                row = startRowId.getAsLong() + position;
            }
            else {
                originalTransaction = BIGINT.getLong(sourcePage.getBlock(ORIGINAL_TRANSACTION_INDEX), position);
                int encodedBucketValue = toIntExact(INTEGER.getLong(sourcePage.getBlock(BUCKET_ID_INDEX), position));
                BucketCodec bucketCodec = BucketCodec.determineVersion(encodedBucketValue);
                bucket = bucketCodec.decodeWriterId(encodedBucketValue);
                statementId = bucketCodec.decodeStatementId(encodedBucketValue);
                row = BIGINT.getLong(sourcePage.getBlock(ROW_ID_INDEX), position);
            }
            return new RowId(originalTransaction, bucket, statementId, row);
        }
    }

    private Set<RowId> getDeletedRows()
    {
        if (deletedRows != null) {
            return deletedRows;
        }

        ImmutableSet.Builder<RowId> deletedRowsBuilder = ImmutableSet.builder();
        for (AcidInfo.DeleteDeltaInfo deleteDeltaInfo : acidInfo.getDeleteDeltas()) {
            Path path = createPath(acidInfo, deleteDeltaInfo, sourceFileName);

            try {
                FileSystem fileSystem = hdfsEnvironment.getFileSystem(identity, path, configuration);
                FileStatus fileStatus = hdfsEnvironment.doAs(identity, () -> fileSystem.getFileStatus(path));

                try (ConnectorPageSource pageSource = pageSourceFactory.createPageSource(fileStatus.getPath(), fileStatus.getLen()).orElseGet(() -> new EmptyPageSource())) {
                    while (!pageSource.isFinished()) {
                        Page page = pageSource.getNextPage();
                        if (page != null) {
                            for (int i = 0; i < page.getPositionCount(); i++) {
                                long originalTransaction = BIGINT.getLong(page.getBlock(ORIGINAL_TRANSACTION_INDEX), i);
                                int encodedBucketValue = toIntExact(INTEGER.getLong(page.getBlock(BUCKET_ID_INDEX), i));
                                BucketCodec bucketCodec = BucketCodec.determineVersion(encodedBucketValue);
                                int bucket = bucketCodec.decodeWriterId(encodedBucketValue);
                                int statement = bucketCodec.decodeStatementId(encodedBucketValue);
                                long row = BIGINT.getLong(page.getBlock(ROW_ID_INDEX), i);
                                RowId rowId = new RowId(originalTransaction, bucket, statement, row);
                                deletedRowsBuilder.add(rowId);
                            }
                        }
                    }
                }
            }
            catch (FileNotFoundException ignored) {
                // source file does not have a delete delta file in this location
            }
            catch (TrinoException e) {
                throw e;
            }
            catch (OrcCorruptionException e) {
                throw new TrinoException(HIVE_BAD_DATA, "Failed to read ORC delete delta file: " + path, e);
            }
            catch (RuntimeException | IOException e) {
                throw new TrinoException(HIVE_CURSOR_ERROR, "Failed to read ORC delete delta file: " + path, e);
            }
        }

        deletedRows = deletedRowsBuilder.build();
        // Not updating memory usage in the loop, when deletedRows are built, as recorded information is propagated
        // to operator memory context via OrcPageSource only at the end of processing of page.
        systemMemoryUsage.setBytes(memorySizeOfRowIdsArray(deletedRows.size()));
        return deletedRows;
    }

    private long memorySizeOfRowIdsArray(int rowCount)
    {
        return sizeOfObjectArray(rowCount) + (long) rowCount * RowId.INSTANCE_SIZE;
    }

    private static Path createPath(AcidInfo acidInfo, AcidInfo.DeleteDeltaInfo deleteDeltaInfo, String fileName)
    {
        Path directory = new Path(acidInfo.getPartitionLocation(), deleteDeltaInfo.getDirectoryName());

        // When direct insert is enabled base and delta directories contain bucket_[id]_[attemptId] files
        // but delete delta directories contain bucket files without attemptId so we have to remove it from filename.
        if (hasAttemptId(fileName)) {
            return new Path(directory, fileName.substring(0, fileName.lastIndexOf("_")));
        }

        if (acidInfo.getOriginalFiles().size() > 0) {
            // Original file format is different from delete delta, construct delete delta file path from bucket ID of original file.
            return AcidUtils.createBucketFile(directory, acidInfo.getBucketId());
        }
        return new Path(directory, fileName);
    }

    private static class RowId
    {
        public static final int INSTANCE_SIZE = ClassLayout.parseClass(RowId.class).instanceSize();

        private final long originalTransaction;
        private final int bucket;
        private final int statementId;
        private final long rowId;

        public RowId(long originalTransaction, int bucket, int statementId, long rowId)
        {
            this.originalTransaction = originalTransaction;
            this.bucket = bucket;
            this.statementId = statementId;
            this.rowId = rowId;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }

            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            RowId other = (RowId) o;
            return originalTransaction == other.originalTransaction &&
                    bucket == other.bucket &&
                    statementId == other.statementId &&
                    rowId == other.rowId;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(originalTransaction, bucket, statementId, rowId);
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("originalTransaction", originalTransaction)
                    .add("bucket", bucket)
                    .add("statementId", statementId)
                    .add("rowId", rowId)
                    .toString();
        }
    }
}
