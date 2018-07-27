/**
 * *****************************************************************************
 * Copyright 2014 Katja Hahn
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
 * ****************************************************************************
 */
package com.github.katjahahn.parser.sections.debug

import DebugSection._
import com.github.katjahahn.parser.IOUtil._
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import com.github.katjahahn.parser.ByteArrayUtil._
import DebugSection._
import java.io.File
import DebugDirectoryKey._
import java.util.Date
import com.github.katjahahn.parser.sections.SectionLoader
import com.github.katjahahn.parser.PELoader
import com.github.katjahahn.parser.sections.SpecialSection
import com.github.katjahahn.parser.PEData
import com.github.katjahahn.parser.StandardField
import com.github.katjahahn.parser.IOUtil
import com.github.katjahahn.parser.MemoryMappedPE
import org.apache.logging.log4j.LogManager
import com.github.katjahahn.parser.sections.SectionLoader.LoadInfo
import com.github.katjahahn.parser.Location
import com.github.katjahahn.parser.PhysicalLocation

/**
 * @author Katja Hahn
 *
 * Represents the debug section of the PE.
 * @param directoryTable the debug directory
 * @param typeDescription the description string for the debug information type
 * @param offset the file offset to the debug directory
 */
class DebugSection private (
  private val directoryTable: DebugDirectory,
  private val typeDescription: String,
  private val debugType: DebugType,
  val offset: Long,
  private val maybeCodeView: Option[CodeviewInfo]) extends SpecialSection {

  override def getOffset(): Long = offset

  def getSize(): Long = debugDirSize

  def getCodeView(): CodeviewInfo =
    if (maybeCodeView.isDefined) maybeCodeView.get
    else throw new IllegalStateException("Code View structure not valid")

  def getDirectoryTable(): java.util.Map[DebugDirectoryKey, StandardField] =
    directoryTable.asJava

  def getPhysicalLocations(): java.util.List[PhysicalLocation] = {
    if (maybeCodeView.isDefined) {
      return (getCodeView().getPhysicalLocations().asScala.toList :+ new PhysicalLocation(offset, getSize)).asJava
    }
    return (new PhysicalLocation(offset, getSize) :: Nil).asJava
  }

  override def isEmpty: Boolean = directoryTable.isEmpty

  override def getInfo(): String =
    s"""|-------------
        |Debug Section
        |-------------
        |
        |${
      directoryTable.values.map(s => s.getKey() match {
        case TYPE            => "Type: " + typeDescription
        case TIME_DATE_STAMP => "Time date stamp: " + getTimeDateStamp().toString
        case _               => s.toString
      }).mkString(NL)}
        |${if (maybeCodeView.isDefined) maybeCodeView.get.getInfo else ""}
        |""".stripMargin

  /**
   * Returns a date object of the time date stamp in the debug section.
   *
   * @return date of the time date stamp
   */
  def getTimeDateStamp(): Date = new Date(get(TIME_DATE_STAMP) * 1000)

  /**
   * Returns a long value of the given key or null if the value doesn't exist.
   *
   * @param key the header key
   * @return long value for the given key of null if it doesn't exist.
   */
  def get(key: DebugDirectoryKey): java.lang.Long =
    if (directoryTable.contains(key))
      directoryTable(key).getValue else null

  /**
   * Returns a string of the type description
   *
   * @return type description string
   */
  def getTypeDescription(): String = typeDescription

  def getDebugType(): DebugType = debugType

}

object DebugSection {

  val logger = LogManager.getLogger(DebugSection.getClass().getName())

  type DebugDirectory = Map[DebugDirectoryKey, StandardField]

  val debugDirSize = 28

  private val debugspec = "debugdirentryspec"

  def main(args: Array[String]): Unit = {
    val file = new File("/home/karsten/samples/WarpBrothers Crypter")
    val data = PELoader.loadPE(file)
    val loader = new SectionLoader(data)
    val debug = loader.loadDebugSection()
    println(debug.getInfo())
  }

  /**
   * Creates an instance of the DebugSection for the given debug bytes.
   *
   * @param loadInfo the load information
   * @return debugsection instance
   */
  def newInstance(li: LoadInfo): DebugSection =
    apply(li.memoryMapped, li.fileOffset, li.va, li.data)

  /**
   * Loads the debug section and returns it.
   *
   * This is just a shortcut to loading the section using the {@link SectionLoader}
   *
   * @return instance of the debug section
   */
  def load(data: PEData): DebugSection =
    new SectionLoader(data).loadDebugSection()

  def apply(mmbytes: MemoryMappedPE, offset: Long, virtualAddress: Long, data: PEData): DebugSection = {
    val format = new SpecificationFormat(0, 1, 2, 3)
    val debugbytes = mmbytes.slice(virtualAddress, virtualAddress + debugDirSize)
    val entries = IOUtil.readHeaderEntries(classOf[DebugDirectoryKey],
      format, debugspec, debugbytes, offset).asScala.toMap
    val debugTypeValue = entries(DebugDirectoryKey.TYPE).getValue
    try {
      val debugType = DebugType.getForValue(debugTypeValue)
      val ptrToRawData = entries(POINTER_TO_RAW_DATA).getValue
      val codeview = CodeviewInfo(ptrToRawData, data.getFile)
      new DebugSection(entries, debugType.getDescription, debugType, offset, codeview)
    } catch {
      case e: IllegalArgumentException =>
        logger.warn("no debug type description found!")
        val description = s"${entries(DebugDirectoryKey.TYPE).getValue} no description available"
        new DebugSection(entries, description, DebugType.UNKNOWN, offset, None)
    }
  }
}