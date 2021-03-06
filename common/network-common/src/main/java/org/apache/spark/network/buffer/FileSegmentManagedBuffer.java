/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.network.buffer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

import com.google.common.base.Objects;
import com.google.common.io.ByteStreams;
import io.netty.channel.DefaultFileRegion;

import org.apache.spark.network.util.JavaUtils;
import org.apache.spark.network.util.LimitedInputStream;
import org.apache.spark.network.util.TransportConf;

/**
 * 由文件中的段支持的管理缓冲区。
 * A {@link ManagedBuffer} backed by a segment in a file.
 */
public final class FileSegmentManagedBuffer extends ManagedBuffer {
  private final TransportConf conf; //就是transprot的配置
  private final File file; //要读取的文件
  private final long offset; //偏移量
  private final long length; //长度

  public FileSegmentManagedBuffer(TransportConf conf, File file, long offset, long length) {
    this.conf = conf;
    this.file = file;
    this.offset = offset;
    this.length = length;
  }

  @Override
  public long size() {
    return length;
  }

  @Override
  public ByteBuffer nioByteBuffer() throws IOException {
    FileChannel channel = null;
    try {
      //RandomAccessFile读取文件
      channel = new RandomAccessFile(file, "r").getChannel();
      //如果缓冲区足够小,只需复制缓冲区,因为内存映射的开销很高
      // Just copy the buffer if it's sufficiently small, as memory mapping has a high overhead.
      if (length < conf.memoryMapBytes()) {//如果小于配置中的内存映射大小就复制缓冲区
        ByteBuffer buf = ByteBuffer.allocate((int) length);
        channel.position(offset);
        while (buf.remaining() != 0) {
          if (channel.read(buf) == -1) {
            throw new IOException(String.format("Reached EOF before filling buffer\n" +
              "offset=%s\nfile=%s\nbuf.remaining=%s",
              offset, file.getAbsoluteFile(), buf.remaining()));
          }
        }
        buf.flip();
        return buf;
      } else {//否则就内存映射模式
        return channel.map(FileChannel.MapMode.READ_ONLY, offset, length);
      }
    } catch (IOException e) {
      try {
        if (channel != null) {
          long size = channel.size();
          throw new IOException("Error in reading " + this + " (actual file length " + size + ")",
            e);
        }
      } catch (IOException ignored) {
        // ignore
      }
      throw new IOException("Error in opening " + this, e);
    } finally {
      JavaUtils.closeQuietly(channel);
    }
  }

  //也很简单,就是用了google的一个工具包来跳过offset之前的流,最后返回offset到length长度的流
  @Override
  public InputStream createInputStream() throws IOException {
    FileInputStream is = null;
    try {
      is = new FileInputStream(file);
      ByteStreams.skipFully(is, offset);
      return new LimitedInputStream(is, length);
    } catch (IOException e) {
      try {
        if (is != null) {
          long size = file.length();
          throw new IOException("Error in reading " + this + " (actual file length " + size + ")",
              e);
        }
      } catch (IOException ignored) {
        // ignore
      } finally {
        JavaUtils.closeQuietly(is);
      }
      throw new IOException("Error in opening " + this, e);
    } catch (RuntimeException e) {
      JavaUtils.closeQuietly(is);
      throw e;
    }
  }

  @Override
  public ManagedBuffer retain() {
    return this;
  }

  @Override
  public ManagedBuffer release() {
    return this;
  }

  @Override
  public Object convertToNetty() throws IOException {
    if (conf.lazyFileDescriptor()) {
      return new DefaultFileRegion(file, offset, length);
    } else {
      FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
      return new DefaultFileRegion(fileChannel, offset, length);
    }
  }

  public File getFile() { return file; }

  public long getOffset() { return offset; }

  public long getLength() { return length; }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
      .add("file", file)
      .add("offset", offset)
      .add("length", length)
      .toString();
  }
}
