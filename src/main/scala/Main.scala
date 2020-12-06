import org.apache.log4j.{Level, Logger}
import org.apache.spark._
//Logger.getLogger("org.apache.spark").setLevel(Level.WARN)
//Logger.getLogger("org.spark-project").setLevel(Level.WARN)
//import org.apache.log4j.{Logger, Level}
//import java.time.format.DateTimeFormatter
import java.time._
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder}
import scala.math.abs
import scala.math.sqrt
import scala.math.pow

case class Station(
                    stationId:Integer,
                    name:String,
                    lat:Double,
                    long:Double,
                    dockcount:Integer,
                    landmark:String,
                    installation:String,
                    notes:String)

case class Trip(
                 tripId:Integer,
                 duration:Integer,
                 startDate:LocalDateTime,
                 startStation:String,
                 startTerminal:Integer,
                 endDate:LocalDateTime,
                 endStation:String,
                 endTerminal:Integer,
                 bikeId: Integer,
                 subscriptionType: String,
                 zipCode: String)

object Main {

  def main(args: Array[String]) {

    //val cfg = new SparkConf().setAppName("Test").setMaster("local[2]")
    //Вставка от локальных переменных
    val Seq(masterURL, tripDataPath, stationDataPath) = args.toSeq
    val cfg = new SparkConf().setAppName("Test").setMaster(masterURL)

    val sc = new SparkContext(cfg)
//    val textFile = sc.textFile("file:///D:/Storage/Desktop/bigdata/list_of_countries_sorted_gini.txt")
//    textFile.foreach(println)
    //val tripData = sc.textFile("file:///D:/Storage/Desktop/bigdata/trips.csv")
    val stationData = sc.textFile(stationDataPath)
    val tripData = sc.textFile(tripDataPath)

//запомним заголовок, чтобы потом его исключить
    val tripsHeader =tripData.first()
    val trips = tripData.filter(row=>row!=tripsHeader).map(row=>row.split(",",-1))


    //val stationData = sc.textFile("file:///D:/Storage/Desktop/bigdata/stations.csv")
    val stationsHeader =stationData.first()
    val stations = stationData.filter(row=>row!=stationsHeader).map(row=>row.split(",",-1))

    stationsHeader.foreach(print)
    println( " " )

    //Создание индексов номеров велостоянок
    val stationsIndexed = stations.keyBy(row=>row(0).toInt)
    stationsIndexed.take(3).foreach(println)

    //Индексирование коллекции trips по колонкам Start Terminal и End Terminal
    val tripsByStartTerminals = trips.keyBy(row=>row(1).toInt)
    val tripsByEndTerminals = trips.keyBy(row=>row(4).toInt)
    tripsByStartTerminals.take(1).foreach(println)
    tripsByEndTerminals.take(1).foreach(println)
    //Объединение по ключу
    val startTrips = stationsIndexed.join(tripsByStartTerminals)
    val endTrips = stationsIndexed.join(tripsByEndTerminals)
    //Вывод полученных ацикличных ориентированных графов
    startTrips.toDebugString
    endTrips.toDebugString
    //Выполнить объявленные графы трансформаций
    startTrips.count()
    endTrips.count()
    // Реализация класса по разделам
    stationsIndexed.partitionBy(new HashPartitioner(trips.partitions.size))
    println(stationsIndexed.partitioner)
    //Конвертация формата данных
    val tripsInternal = trips.mapPartitions(rows => {
      val timeFormat =
        DateTimeFormatter.ofPattern("yyyy-MM-dd H:m")
      rows.map( row =>
        new Trip(tripId=row(0).toInt,
          duration=row(1).toInt,
          startDate= LocalDateTime.parse(row(2), timeFormat),
          startStation=row(3),
          startTerminal=row(4).toInt,
          endDate=LocalDateTime.parse(row(5), timeFormat),
          endStation=row(6),
          endTerminal=row(7).toInt,
          bikeId=row(8).toInt,
          subscriptionType=row(9),
          zipCode=row(10)))})

    //Изучение полученных данных
    println(tripsInternal.first)
    println(tripsInternal.first.startDate)

    val stationsInternal = stations.map(row=>
      new Station(stationId=row(0).toInt,
        name=row(1),
        lat=row(2).toDouble,
        long=row(3).toDouble,
        dockcount=row(4).toInt,
        landmark=row(5),
        installation=row(6),
        notes=null))
    println(stationsInternal.first)
    //коллекция пар ключ-значение
    val tripsByStartStation = tripsInternal.keyBy(record =>
      record.startStation)
    //Рассчитаем среднее время поездки для каждого стартового парковочного места
    val avgDurationByStartStation = tripsByStartStation
      .mapValues(x=>x.duration)
      .groupByKey()
      .mapValues(col=>col.reduce((a,b)=>a+b)/col.size)
    //Выведем первые 10 результатов
    avgDurationByStartStation.take(10).foreach(println)
    val avgDurationByStartStation2 = tripsByStartStation
      .mapValues(x=>x.duration)
      .aggregateByKey((0,0))(
        (acc, value) => (acc._1 + value, acc._2 + 1),
        (acc1, acc2) => (acc1._1+acc2._1, acc1._2+acc2._2))
      .mapValues(acc=>acc._1/acc._2)
    avgDurationByStartStation2.take(10).foreach(println)

    /*val firstGrouped = tripsByStartStation
      .groupByKey()
      .mapValues(x =>
        x.toList.sortWith((trip1, trip2) =>
          trip1.startDate.compareTo(trip2.startDate)<0)) */

    val firstGrouped = tripsByStartStation
      .reduceByKey((trip1,trip2) =>
        if (trip1.startDate.compareTo(trip2.startDate)<0)
          trip1 else trip2)
    firstGrouped.take(1).foreach(println)
    //Задания
    // 1. Найти велосипед с максимальным пробегом
    val tripOfBikes = tripsInternal.keyBy(record => record.bikeId)
      .mapValues(x=>x.duration)
      .reduceByKey  ((trip1,trip2) => trip1 + trip2 )
      .sortBy( a => a._2,ascending = false)
    val maxTrip = tripOfBikes.map( a => a._1).first
    println("МАКСИМАЛЬНЫ   ПРОБЕГ :"+ maxTrip)
    // 2. Найти наибольшее расстояние между станциями
    val dataOfStations = stationsInternal.cartesian(stationsInternal)
      .map {
        case (station1, station2) =>
          (station1.long, station1.lat, station1.stationId,
            station2.long, station2.lat, station2.stationId)
      }
    val maxStationDistance = dataOfStations.map{ row => (sqrt(pow(row._1 - row._4,2) + pow(row._2 - row._5,2)), row._3 ,  row._6 ) }
      .sortBy( a => a._1,ascending = false)
    maxStationDistance.collect().take(1).foreach(println)

    // 3. Путь велосипеда с максимальным пробегом через станции
    val  wayOfMaxTrip = tripsInternal.keyBy(record => record.bikeId).lookup(maxTrip).map(x=>x.startStation)
    println("ПУТЬ ВЕЛОСИПЕДА С МАКСИМАЛЬНЫМ ПРОБЕГОМ ЧЕРЕЗ СТАНЦИИ :"+wayOfMaxTrip)

    //4. Найти количество велосипедов в системе
    val bikesCount = tripsInternal.keyBy(record => record.bikeId).groupByKey().count()
    println("КОЛИЧЕСТВО ВЕЛОСИПЕДОВ В СИСТЕМЕ :"+bikesCount)

    //5. Найти пользователей потративших на поездки более 3 часов
    val bikeseTimeMoreThreeOurs= tripsInternal.keyBy(record => record.zipCode).mapValues((x)=> x.duration ).filter(v => v._2 > 180)
    bikeseTimeMoreThreeOurs.take(10).foreach(println)

    //ВЫВЕДЕМ ВСЕ ИТОГИ ЗАДАНИЯ

    println("МАКСИМАЛЬНЫ   ПРОБЕГ :"+ maxTrip)
    maxStationDistance.collect().take(1).foreach(println)
    println("ПУТЬ ВЕЛОСИПЕДА С МАКСИМАЛЬНЫМ ПРОБЕГОМ ЧЕРЕЗ СТАНЦИИ :"+wayOfMaxTrip)
    println("КОЛИЧЕСТВО ВЕЛОСИПЕДОВ В СИСТЕМЕ :"+bikesCount)
    bikeseTimeMoreThreeOurs.take(10).foreach(println)

    sc.stop()
  }

}