package com.innotech.data.sql.executor

import java.io._
import java.text.SimpleDateFormat
import java.util.Date

import org.apache.commons.lang3.StringUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.io.IOUtils
import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer
import scala.util.parsing.json.JSONObject


/**
  * @author 李富强
  *         2017/09/19
  */
object SparkApplication {

    def main(args: Array[String]): Unit = {
        val sql1 = "select * from rpt_qukan.member_sys limit 2000"
        val sql2 = "select * from rpt_qukan.member_sys limit 1000"
        val hdfsPath1 = "/user/spark/sql_executor_output/test1"
        val hdfsPath2 = "/user/spark/sql_executor_output/test2"

        val localPath1 = "/var/lib/spark/lifq/sql_executor_output/test1_data.csv"
        val localPath2 = "/var/lib/spark/lifq/sql_executor_output/test2_data.csv"

        val sqln1 = SQLNode(sql1, SQLType.SELECT, ",", hdfsPath1, localPath1)
        val sqln2 = SQLNode(sql2, SQLType.SELECT, ",", hdfsPath2, localPath2)
      //  println(sqln1.toString)
       execute(Array[SQLNode](sqln1, sqln2), "jobTest", "")
    }

    val logger = LoggerFactory.getLogger(getClass)
    val failInfoBasePath = "/user/spark/sql_executor_output/failed/"

    /** 执行sparl任务，根据传入的sql语句，执行相应的操作。目前支持两种操作：insert和select。
      * insert语句只能将数据写入到指定的表中。
      * select语句将查询产生的结果保存到文件中
      *
      * @param sqls    List[SQLNode]类型，SQL内容
      * @param jobName job名称
      * @param warehouseLocation
      */
    def execute(sqls: Array[SQLNode], jobName: String, warehouseLocation: String): Unit = {
        //参数校验
        require(!sqls.isEmpty, "sqls size cannot be zero")
        require(StringUtils.isNotEmpty(jobName), "job name must be set")

        val sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss")
        val job_time = sdf.format(new Date())

        val failedSqls = new ArrayBuffer[SQLNode]()
        val spark = SparkSession
          .builder()
          .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
          .appName(jobName)
          //  .config("spark.sql.warehouse.dir", "/warehouse")
          .enableHiveSupport()
          .getOrCreate()

        // 根据输出类型，选择是insert操作还是select操作。
        // 当sqlNode的outputtype为1时，选择insert操作，当值为2时，选择select操作。
        sqls.foreach(sqlnode => {
            logger.info(s"${sqlnode} start ")
            try {
                sqlnode.sqlType match {
                    case SQLType.INSERT => {
                        //执行insert语句
                        spark.sql(sqlnode.sql)
                    }
                    case SQLType.SELECT => {
                        // 执行select语句，repartition进行重分区，生成一个分区，这样结果可以只保存到一个文件中。
                        // 每行数据根据设定分隔符，生成以该分隔符间隔的行数据
                        val df = spark.sql(sqlnode.sql)
                        val dataRdd = df.rdd.map(row => {
                            val string = new StringBuilder()
                            //row.mkString(sqlnode.csvSeperator)
                            //以指定的分隔符将row转换成字符串
                            for (i <- 0 until row.length) {
                                string.append(row.get(i))
                                if (i != row.length - 1) {
                                    string.append(sqlnode.csvSeperator)
                                }
                            }
                            string.toString()
                        }).repartition(1).saveAsTextFile(sqlnode.hdfsOutDir)

                        //写title到文件
                        val columns = df.columns.reduce(_ + sqlnode.csvSeperator + _)
                        writeDataToHdfs(new Path(sqlnode.hdfsOutDir + Path.SEPARATOR + "title.csv"), List[String](columns.toString()))

                        //写入本地文件,生成数据文件，包含表头和数据
                        mergeFile(sqlnode.hdfsOutDir + Path.SEPARATOR + "part-00000", sqlnode.hdfsOutDir + Path.SEPARATOR + "title.csv", sqlnode.hdfsOutDir + Path.SEPARATOR + "data.csv")
                    }
                }
            } catch {
                case e: Exception => {
                    failedSqls.append(sqlnode)
                    //运行失败，删除产生的文件
                    deleteHdfsFile(sqlnode.hdfsOutDir)
                }
            }

            logger.info(s"${sqlnode} has finished.")
        })
        spark.stop()
        logger.info(s"SQL executor job ${jobName} has finished.")

        //保存运行失败的信息
        writeFailJobInfo(failedSqls, failInfoBasePath + job_time)
    }

    def writeDataToHdfs(path: Path, data: List[String]): Unit = {
        var out: OutputStream = null
        try {
            val conf = new Configuration()
            val fs = FileSystem.get(conf)
            val out = fs.create(path)
            data.foreach(line => out.write(line.getBytes))
            out.write("\n".getBytes())
            out.close()
        } catch {
            case e: Exception => e.printStackTrace()
        } finally {
            if (out != null) {
                out.close()
            }
        }
    }

    def writeToLocalFile(path: String, data: List[String]): Unit = {
        val writer = new OutputStreamWriter(new FileOutputStream(path))
        data.foreach(line => {
            writer.write(line)
            writer.write("\n")
        })
        writer.close()
    }

    def writeHdfsFileToLocal(hdfsPath: Path, titleFilePath: Path, localFilePath: String): Unit = {
        val conf = new Configuration()
        val fs = FileSystem.get(conf)

        val outputStream = new FileOutputStream(localFilePath)

        val titleFileInputStream = fs.open(titleFilePath)
        IOUtils.copyBytes(titleFileInputStream, outputStream, 10240)
        titleFileInputStream.close()

        outputStream.write("\n".getBytes())
        var hdfsInputStream = fs.open(hdfsPath)
        IOUtils.copyBytes(hdfsInputStream, outputStream, 10240)
        hdfsInputStream.close()

        outputStream.close()

        fs.close()
    }

    def mergeFile(hdfsPath: String, titleFilePath: String, destPath: String): Unit = {
        val conf = new Configuration()
        val fs = FileSystem.get(conf)

        val outputStream = fs.create(new Path(destPath))

        val titleFileInputStream = fs.open(new Path(titleFilePath))
        IOUtils.copyBytes(titleFileInputStream, outputStream, 10240)
        titleFileInputStream.close()

        // outputStream.write("\n".getBytes())

        var hdfsInputStream = fs.open(new Path(hdfsPath))
        IOUtils.copyBytes(hdfsInputStream, outputStream, 10240)
        hdfsInputStream.close()

        outputStream.close()
    }

    def writeFailJobInfo(SqlNodes: Seq[SQLNode], path: String): Unit = {
        val conf = new Configuration()
        val fs = FileSystem.get(conf)
        val outputStream = fs.create(new Path(path))

        SqlNodes.foreach(sqlnode => {
            outputStream.write(sqlnode.toString.getBytes())
            outputStream.write("\n".getBytes())
        })

        outputStream.close()
    }

    def deleteHdfsFile(path: String): Unit = {
        val conf = new Configuration()
        val fs = FileSystem.get(conf)
        fs.delete(new Path(path), true)
    }
}

case class SQLNode(sql: String, sqlType: Int, csvSeperator: String, hdfsOutDir: String, localPath: String) {
    require(StringUtils.isNotEmpty(sql), "sql value can not be empty")
    require(sqlType.equals(SQLType.INSERT) || sqlType.equals(SQLType.SELECT), "sqlType value must be 1 or 2")
    require(StringUtils.isNotEmpty(csvSeperator), "csvSeperator value can not be empty")
    require(StringUtils.isNotEmpty(hdfsOutDir), "outputDir value can not be empty")
    require(StringUtils.isNotEmpty(localPath), "outputDir value can not be empty")

    override def toString(): String = {
        val map = Map("sql" ->  sql,"sqlType" -> sqlType,"csvSeperator" -> csvSeperator,"hdfsOutDir"-> hdfsOutDir,"localPath"->localPath)
        JSONObject.apply(map).toString()
    }
}

object SQLType {
    val INSERT = 1
    val SELECT = 2
}




