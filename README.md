
# metrix

[![Build Status](https://travis-ci.org/hmrc/metrix.svg?branch=master)](https://travis-ci.org/hmrc/metrix) [ ![Download](https://api.bintray.com/packages/hmrc/releases/metrix/images/download.svg) ](https://bintray.com/hmrc/releases/metrix/_latestVersion)

## Example construction
``` scala
    val exclusiveTimePeriodLock = new ExclusiveTimePeriodLock {
      override val repo: LockRepository = LockRepository
      override val lockId: String = "write-your-lock-id"
      override val holdLockFor =  new JodaDuration(lockExpiryTime.toMillis)
    }
    
  val sources: List[MetricSource] = AddYourMetricSourcesHere
    val metricOrchestrator = new MetricOrchestrator(
      sources,
      exclusiveTimePeriodLock,
      repositories.MongoMetricRepository,
      MetricsRegistry.defaultRegistry
    )
```
## Example usage    
``` scala
    metricOrchestrator
        .attemptToUpdateAndRefreshMetrics(
          skipReportingOn = optionalFilterMethodForPersistedMetrics()
        ).map(_.andLogTheResult())
        .recover { case e: RuntimeException => Logger.error(s"An error occurred processing metrics: ${e.getMessage}", e) }
```        
        
## Installing
 
Include the following dependency in your SBT build
 
``` scala
resolvers += Resolver.bintrayRepo("hmrc", "releases")
 
libraryDependencies += "uk.gov.hmrc" %% "metrix" % "[INSERT-VERSION]" 
```

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
    
