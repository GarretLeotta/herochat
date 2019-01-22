package herochat

import akka.dispatch.{DispatcherPrerequisites, ExecutorServiceFactory, ExecutorServiceConfigurator}
import com.typesafe.config.Config
import java.util.concurrent.{ExecutorService, AbstractExecutorService, ThreadFactory, TimeUnit}
import java.util.Collections
import javafx.application.Platform

object ScalaFXExecutorService extends AbstractExecutorService {
  def execute(command: Runnable) = Platform.runLater(command)

  def shutdown(): Unit = ()

  def shutdownNow() = Collections.emptyList[Runnable]

  def isShutdown = false

  def isTerminated = false

  def awaitTermination(l: Long, timeUnit: TimeUnit) = true
}

class ScalaFXEventThreadExecutorServiceConfigurator(config: Config, prerequisites: DispatcherPrerequisites) extends ExecutorServiceConfigurator(config, prerequisites) {
  private val f = new ExecutorServiceFactory {
    def createExecutorService: ExecutorService = ScalaFXExecutorService
  }

  def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = f
}
