package org.biodatageeks.sequila.tests.pileup.converters

import org.apache.spark.sql.SequilaSession
import org.biodatageeks.sequila.pileup.PileupWriter
import org.biodatageeks.sequila.tests.pileup.PileupTestBase
import org.biodatageeks.sequila.utils.{Columns}

class SequilaDataPrep extends PileupTestBase {


  val queryQual =
    s"""
       |SELECT contig, pos_start, pos_end, ref, coverage, altmap_to_str(alts_to_char(${Columns.ALTS})) as ${Columns.ALTS} , qualsmap_to_str(to_charmap(${Columns.QUALS})) as ${Columns.QUALS}
       |FROM  pileup('$tableName', '${sampleId}', '$referencePath', true, true)
                         """.stripMargin


  test("alts,quals: one partition") {
    val ss = SequilaSession(spark)

    val bdgRes = ss.sql(queryQual).orderBy("contig", "pos_start")
    bdgRes.show(10)
    PileupWriter.save(bdgRes, "sequilaQualsBlocks.csv")
  }


}
