// Imports for Spark, Delta, and Kafka
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger
import io.delta.tables._
import org.apache.spark.sql.types._
import org.apache.spark.sql.DataFrame
import java.net.URI
import org.apache.hadoop.fs.{FileSystem, Path}

println("=== Début d'initialisation de Delta Lake ===")

// Lecture des variables d'environnement
val topicName = Option(System.getenv("TOPIC_NAME")).getOrElse("sport.sport_advantages.sport_activities")
val bootstrapServers = Option(System.getenv("KAFKA_SERVERS")).getOrElse("redpanda:9092")

// Affichage des variables d'environnement pour debug
println("=== Variables d'environnement ===")
println(s"TOPIC_NAME: $topicName")
println(s"KAFKA_SERVERS: $bootstrapServers")

println("=== ENV VAR DEBUG ===")
println(s"MINIO_ROOT_USER: ${sys.env.getOrElse("MINIO_ROOT_USER", "Not found")}")
println(s"MINIO_ROOT_PASSWORD: ${sys.env.getOrElse("MINIO_ROOT_PASSWORD", "Not found")}")

// Configuration pour MinIO (service local dans docker-compose)
spark.conf.set("spark.hadoop.fs.s3a.endpoint", "http://minio:9000")
spark.conf.set("spark.hadoop.fs.s3a.connection.ssl.enabled", "false")
spark.conf.set("spark.hadoop.fs.s3a.endpoint.region", "us-east-1")
spark.conf.set("spark.hadoop.fs.s3a.path.style.access", "true")
spark.conf.set("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
spark.conf.set("spark.hadoop.fs.s3a.access.key", sys.env.getOrElse("MINIO_ROOT_USER", "minio_user"))
spark.conf.set("spark.hadoop.fs.s3a.secret.key", sys.env.getOrElse("MINIO_ROOT_PASSWORD", "minio_password"))
spark.conf.set("spark.hadoop.fs.s3a.connection.ssl.enabled", "false")

// Si nécessaire, désactiver la validation de région
spark.conf.set("spark.hadoop.fs.s3a.change.detection.mode", "none")
spark.conf.set("spark.hadoop.fs.s3a.endpoint.region", "us-east-1")

// Définir votre bucket
val bucketName = "delta-tables"

// Dans le test d'écriture :
val testPath = s"s3a://delta-tables/test-file.txt"
val path = new Path(testPath)
val fs = FileSystem.get(new URI(testPath), spark.sparkContext.hadoopConfiguration)
  
try {
    if (!fs.exists(path)) {
    println(s"Le fichier n'existe pas, création en cours: $testPath")
    // Créer le fichier
    spark.sparkContext.parallelize(Seq("test")).saveAsTextFile(testPath)
    println("Écriture TEST réussie dans MinIO!")
  } else {
    println(s"Le fichier existe déjà: $testPath")
  }
} catch {
  case e: Exception => traceException(e, "ÉCHEC de l'écriture TEST")
}

// Dans SaveDelta.scala
println("=== Test d'accès à MinIO ===")
try {
  val df = spark.createDataFrame(Seq(("test", 1))).toDF("data", "value")
  df.write.mode("overwrite").csv(testPath)
  println("Écriture de test réussie dans MinIO!")
} catch {
  case e: Exception => traceException(e, "Échec de l'écriture de test dans MinIO")
}

// Fonction pour tracer les exceptions de manière détaillée
def traceException(e: Exception, message: String): Unit = {
  println(s"ERROR: $message")
  println(s"Exception type: ${e.getClass.getName}")
  println(s"Exception message: ${e.getMessage}")
  println("Stack trace:")
  e.printStackTrace()
  
  // Get cause if available
  var cause = e.getCause
  if (cause != null) {
    println(s"Causé par: ${cause.getMessage}")
  }
}

// Define the schema for our activity data
val activitySchema = StructType(Array(
  StructField("id", IntegerType, false),
  StructField("id_employee", IntegerType, false),
  StructField("start_datetime", StringType, false),
  StructField("sport_type", StringType, false),
  StructField("activity_duration", IntegerType, false),
  StructField("distance", DoubleType, true),
  StructField("comment", StringType, true)
))

println("\n=== Configuration de la connexion Redpanda ===")

// Try reading from Redpanda in a fault-tolerant way
var redpandaStream: DataFrame = null

try {
  // Lecture du stream depuis Redpanda
  redpandaStream = spark
    .readStream
    .format("kafka")
    .option("kafka.bootstrap.servers", bootstrapServers)
    .option("subscribe", topicName)
    .option("startingOffsets", "earliest")
    .option("failOnDataLoss", "false") 
    .option("kafka.group.id", "spark-delta-lake-group")
    .load()
  
  println("Lecteur de flux Redpanda créé avec succès")
  println("=== Schéma du flux Redpanda ===")
  redpandaStream.printSchema()
} catch {
  case e: Exception => 
    traceException(e, "Error creating Redpanda stream reader")
    // Provide a fallback to continue script execution for testing
    println("Using a dummy dataframe for testing...")
    import spark.implicits._
    redpandaStream = spark.readStream
      .format("rate")
      .option("rowsPerSecond", 1)
      .load()
      .selectExpr("CAST(timestamp AS STRING) AS key", "CAST(value AS STRING) AS value")
}

println("\n=== Transformer les données ===")

// Extraction directe par JSON
try {
  val rawDataFrame = redpandaStream
    .selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)")

  // Utiliser get_json_object pour extraire directement les valeurs
  val activityData = rawDataFrame
    .select(
      get_json_object(col("value"), "$.payload.after.id").cast(IntegerType).as("id"),
      get_json_object(col("value"), "$.payload.after.id_employee").cast(IntegerType).as("id_employee"),
      // Convertir le timestamp microseconde en date lisible
      expr("from_unixtime(cast(get_json_object(value, '$.payload.after.start_datetime') as long) / 1000000)").as("start_datetime"),
      get_json_object(col("value"), "$.payload.after.sport_type").as("sport_type"),
      get_json_object(col("value"), "$.payload.after.activity_duration").cast(IntegerType).as("activity_duration"),
      get_json_object(col("value"), "$.payload.after.distance").cast(DoubleType).as("distance"),
      get_json_object(col("value"), "$.payload.after.comment").as("comment")
    )
    .filter(col("id").isNotNull)  // Filtrer les lignes invalides

  println("=== Schéma des données transformées ===")
  activityData.printSchema()
  
  println("\n=== Début de l'écriture du flux du lac Delta ===")
  
  // Ecrire dans Delta Lake en mode streaming - with more lenient settings
  val query = activityData
    .writeStream
    .format("delta")
    .outputMode("append")
    .option("checkpointLocation", s"s3a://$bucketName/checkpoints/sport_activities")
    .trigger(Trigger.ProcessingTime("30 seconds"))
    .start(s"s3a://$bucketName/tables/sport_activities")
  
  // Affichage du statut du stream
  println(s"Statut de la requête: ${query.status}")
  println("Le flux a démarré avec succès!")
  println("Surveillance de l'état du flux toutes les 30 secondes. Appuyez sur Ctrl+C pour arrêter....")
  
  // Surveiller périodiquement l'état du flux
  var isRunning = true
  var totalProcessedRows = 0L
  var batchCount = 0
  var lastBatchTimestamp = ""

  while (isRunning) {
    try {
      println(s"\n=== Stream Status at ${java.time.LocalDateTime.now()} ===")
      println(s"Status: ${query.status}")
      
      if (query.recentProgress.nonEmpty) {
        val lastProgress = query.recentProgress.last
        val currentBatchRows = lastProgress.numInputRows
        
        // Éviter de compter deux fois le même batch
        val currentTimestamp = lastProgress.timestamp
        if (currentTimestamp != lastBatchTimestamp) {
          // Mettre à jour les compteurs uniquement s'il s'agit d'un nouveau batch
          totalProcessedRows += currentBatchRows
          batchCount += 1
          lastBatchTimestamp = currentTimestamp
          
          // Afficher les statistiques de transactions
          println(s"Transactions dans ce batch: ${currentBatchRows}")
          println(s"Total des transactions traitées: ${totalProcessedRows}")
          println(s"Nombre de batches traités: ${batchCount}")
          
          if (batchCount > 0) {
            println(s"Débit moyen (lignes/batch): ${totalProcessedRows.toDouble / batchCount}")
            println(s"Débit d'entrée: ${lastProgress.inputRowsPerSecond} lignes/sec")
            println(s"Débit de traitement: ${lastProgress.processedRowsPerSecond} lignes/sec")
          }
        }
      } else {
        println("Aucun événement de progression pour le moment")
      }
      
      // Vérifier les données dans la table Delta (votre code existant)
      try {
        val checkData = spark.read.format("delta").load(s"s3a://$bucketName/tables/sport_activities")
        val recordCount = checkData.count()
        println(s"Nombre d'enregistrements actuels dans la table Delta: ${recordCount}")
        
        // Si les chiffres ne correspondent pas, expliquer pourquoi
        if (recordCount != totalProcessedRows) {
          println(s"Note: La différence entre les transactions traitées (${totalProcessedRows}) et les enregistrements en base (${recordCount}) peut être due à:")
          println("- Des transactions en attente d'écriture")
          println("- Des données préexistantes dans la table")
          println("- Des opérations de nettoyage ou de déduplication")
        }
        
        if (recordCount > 0) {
          println("Exemple de données de la table Delta:")
          checkData.show(5, false)
        }
      } catch {
        case e: Exception => println(s"La table Delta n'est pas encore interrogeable: ${e.getMessage}")
      }
      
      // Ajouter des métriques plus détaillées par type d'activité
      try {
        val deltaTable = spark.read.format("delta").load(s"s3a://$bucketName/tables/sport_activities")
        deltaTable.createOrReplaceTempView("sport_activities")
        
        println("\n=== Répartition des transactions par type de sport ===")
        spark.sql("""
          SELECT sport_type, COUNT(*) as count 
          FROM sport_activities 
          GROUP BY sport_type 
          ORDER BY count DESC
          LIMIT 5
        """).show()
      } catch {
        case e: Exception => println("Analyse par type de sport pas encore disponible")
      }
      
      Thread.sleep(30000)  // 30 secondes
    } catch {
      case e: InterruptedException => 
        println("Surveillance du flux interrompue")
        isRunning = false
      case e: Exception =>
        println(s"Erreur lors de la surveillance: ${e.getMessage}")
    }
  }
  
  println("En attente de la fin du flux...")
  query.awaitTermination()
} catch {
  case e: Exception => 
    traceException(e, "Error in streaming process")
    println("Script completed with errors")
}

