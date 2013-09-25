import org.vertx.java.core.Handler

import org.quartz.SchedulerFactory
import org.quartz.Scheduler
import org.quartz.JobDetail
import org.quartz.Job
import org.quartz.JobDataMap
import org.quartz.Trigger
import org.quartz.TriggerBuilder
import org.quartz.JobBuilder
import org.quartz.CronScheduleBuilder
import org.quartz.CalendarIntervalScheduleBuilder
import org.quartz.SimpleScheduleBuilder
import static org.quartz.DateBuilder.MONDAY

SchedulerFactory schedFact = new org.quartz.impl.StdSchedulerFactory();

  Scheduler sched = schedFact.getScheduler();

  sched.start();

  JobDetail fetchUpdatesJob = JobBuilder.newJob(FetchUpdatesJob.class)
      .withIdentity("fetchUpdatesJob", "group1")
      .usingJobData(new JobDataMap([vertx: vertx]))
      .build();

  Trigger fetchUpdatesTrigger = TriggerBuilder.newTrigger()
      .withIdentity("fetchUpdatesJobTrigger", "group1")
      .startNow()
      .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(20, 30))
      .build();

  sched.scheduleJob(fetchUpdatesJob, fetchUpdatesTrigger)

  JobDetail generateDigestJob = JobBuilder.newJob(GenerateDigestJob.class)
      .withIdentity("generateDigestJob", "group1")
      .usingJobData(new JobDataMap([vertx: vertx]))
      .build();

  Trigger generateDigestTrigger = TriggerBuilder.newTrigger()
      .withIdentity("generateDigestJobTrigger", "group1")
      .startNow()
      .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(22, 0))
      .build();

  sched.scheduleJob(generateDigestJob, generateDigestTrigger);

    /** Test email service **/
  JobDetail testEmailJob = JobBuilder.newJob(TestEmailJob.class)
      .withIdentity("testEmailJob", "group1")
      .usingJobData(new JobDataMap([vertx: vertx]))
      .build();

  Trigger testEmailTrigger = TriggerBuilder.newTrigger()
      .withIdentity("testEmailJobTrigger", "group1")
      .withSchedule(SimpleScheduleBuilder.repeatMinutelyForTotalCount(2))
      .build();

  sched.scheduleJob(testEmailJob, testEmailTrigger);

/** Test email service **/
//JobDetail printTest = JobBuilder.newJob(PrintTestJob.class)
//        .withIdentity("printTestJob", "group1")
//        .usingJobData(new JobDataMap([vertx: vertx]))
//        .build();
//
//Trigger printTestTrigger = TriggerBuilder.newTrigger()
//        .withIdentity("printTestJobTrigger", "group1")
//        .startNow()
//        .withSchedule(SimpleScheduleBuilder.repeatMinutelyForTotalCount(3))
//        .build();
//
//sched.scheduleJob(printTest, printTestTrigger);


println "In JobService"
println "printTest = $printTest"
println "printTestTrigger = $printTestTrigger"


/*
//Comment out for dev testing
JobDetail seMaintenanceJob = JobBuilder.newJob(SEMaintenanceJob.class)
        .withIdentity("seMaintenanceJob", "group1")
        .usingJobData(new JobDataMap([vertx: vertx]))
        .build()
int intervalDays = 10
Trigger seMaintenanceTrigger = TriggerBuilder.newTrigger()
        .withIdentity("seMaintenanceJobTrigger", "group1")
        .startNow()
        .withSchedule(CalendarIntervalScheduleBuilder.calendarIntervalSchedule().withIntervalInDays(intervalDays))
        .build()
sched.scheduleJob(seMaintenanceJob, seMaintenanceTrigger)
*/

println "JobService - DONE"