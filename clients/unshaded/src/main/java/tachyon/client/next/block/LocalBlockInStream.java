/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.client.next.block;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import com.google.common.io.Closer;
import tachyon.client.next.ClientOptions;
import tachyon.util.io.BufferUtils;
import tachyon.worker.next.WorkerClient;

/**
 * This class provides a streaming API to read a block in Tachyon. The data will be directly read
 * from the local machine's storage.
 */
public class LocalBlockInStream extends BlockInStream {

  private final long mBlockId;
  private final BSContext mContext;
  private final WorkerClient mWorkerClient;
  private final ByteBuffer mData;

  private boolean mClosed;

  public LocalBlockInStream(long blockId, ClientOptions options) throws IOException {
    mBlockId = blockId;
    mClosed = false;
    mContext = BSContext.INSTANCE;
    mWorkerClient = mContext.acquireWorkerClient();
    String blockPath = mWorkerClient.lockBlock(blockId);

    if (null == blockPath) {
      // TODO: Handle this error case better
      throw new IOException("Block is not available on local machine");
    }

    // Map the data to the blockData byte buffer
    Closer closer = Closer.create();
    try {
      RandomAccessFile localFile = closer.register(new RandomAccessFile(blockPath, "r"));
      long fileLength = localFile.length();
      FileChannel localFileChannel = closer.register(localFile.getChannel());
      mData = localFileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileLength);
    } finally {
      closer.close();
    }
  }

  @Override
  public void close() throws IOException {
    if (mClosed) {
      return;
    }
    mWorkerClient.unlockBlock(mBlockId);
    mContext.releaseWorkerClient(mWorkerClient);
    // TODO: Evaluate if this is necessary
    BufferUtils.cleanDirectBuffer(mData);
    mClosed = true;
  }

  @Override
  public int read() throws IOException {
    failIfClosed();
    if (mData.remaining() == 0) {
      close();
      return -1;
    }
    return mData.get() & 0xFF;
  }

  @Override
  public int read(byte[] b) throws IOException {
    failIfClosed();
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    failIfClosed();
    if (b == null) {
      throw new NullPointerException();
    } else if (off < 0 || len < 0 || len > b.length - off) {
      throw new IndexOutOfBoundsException();
    } else if (len == 0) {
      return 0;
    }

    int ret = Math.min(len, mData.remaining());
    if (ret == 0) {
      close();
      return -1;
    }
    mData.get(b, off, ret);
    return ret;
  }

  public void seek(long pos) throws IOException {
    failIfClosed();
    if (pos < 0) {
      throw new IOException("Seek position is negative: " + pos);
    } else if (pos > mData.limit()) {
      throw new IOException("Seek position is past buffer limit: " + pos + ", Buffer Size = "
          + mData.limit());
    }
    mData.position((int) pos);
  }

  @Override
  public long skip(long n) throws IOException {
    failIfClosed();
    if (n <= 0) {
      return 0;
    }

    int ret = mData.remaining();
    if (ret > n) {
      ret = (int) n;
    }
    mData.position(mData.position() + ret);
    return ret;
  }

  private void failIfClosed() throws IOException {
    if (mClosed) {
      throw new IOException("Cannot do operations on a closed BlockInStream");
    }
  }
}
