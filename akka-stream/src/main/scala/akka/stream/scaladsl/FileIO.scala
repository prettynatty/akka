/**
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import java.io.File
import java.nio.file.StandardOpenOption
import java.util

import akka.stream.ActorAttributes
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.io._
import akka.stream.io.IOResult
import akka.util.ByteString

import scala.collection.JavaConversions._
import scala.concurrent.Future

/**
 * Java API: Factories to create sinks and sources from files
 */
object FileIO {

  import Sink.{ shape ⇒ sinkShape }
  import Source.{ shape ⇒ sourceShape }

  /**
   * Creates a Source from a Files contents.
   * Emitted elements are `chunkSize` sized [[akka.util.ByteString]] elements,
   * except the final element, which will be up to `chunkSize` in size.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * It materializes a [[Future]] of [[IOResult]] containing the number of bytes read from the source file upon completion,
   * and a possible exception if IO operation was not completed successfully.
   *
   * @param f         the File to read from
   * @param chunkSize the size of each read operation, defaults to 8192
   */
  def fromFile(f: File, chunkSize: Int = 8192): Source[ByteString, Future[IOResult]] =
    new Source(new FileSource(f, chunkSize, DefaultAttributes.fileSource, sourceShape("FileSource")))

  /**
   * Creates a Sink which writes incoming [[ByteString]] elements to the given file and either overwrites
   * or appends to it.
   *
   * Materializes a [[Future]] of [[IOResult]] that will be completed with the size of the file (in bytes) at the streams completion,
   * and a possible exception if IO operation was not completed successfully.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def toFile(f: File, options: Set[StandardOpenOption] = Write): Sink[ByteString, Future[IOResult]] =
    new Sink(new FileSink(f, options, DefaultAttributes.fileSink, sinkShape("FileSink")))

  def toFile(f: File, options: util.Set[StandardOpenOption]): Sink[ByteString, Future[IOResult]] =
    new Sink(new FileSink(f, options, DefaultAttributes.fileSink, sinkShape("FileSink")))

  import java.nio.file.StandardOpenOption._

  val Write = Set(WRITE, CREATE)
  val Append = Set(APPEND)
}
