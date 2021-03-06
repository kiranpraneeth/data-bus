/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.inmobi.databus.purge;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.inmobi.databus.AbstractService;
import com.inmobi.databus.Cluster;
import com.inmobi.databus.DatabusConfig;
import com.inmobi.databus.DatabusConfigParser;
import com.inmobi.databus.Stream;
import com.inmobi.databus.Stream.DestinationStreamCluster;
import com.inmobi.databus.Stream.StreamCluster;
import com.inmobi.databus.utils.CalendarHelper;

/*
 * Assumptions
 * (i) One data Purger Service for a cluster
 */

public class DataPurgerService extends AbstractService {
  private static final Log LOG = LogFactory.getLog(DataPurgerService.class);

  private final Cluster cluster;
  private final FileSystem fs;
  private final Integer defaulttrashPathRetentioninHours;
  private final Integer defaultstreamPathRetentioninHours;
  private Map<String, Integer> streamRetention;
  private Set<Path> streamsToPurge;
  private DateFormat dateFormat = new SimpleDateFormat("yyyy:MM:dd:HH:mm");
  private static long MILLISECONDS_PER_HOUR = 60 * 60 * 1000;

  public DataPurgerService(DatabusConfig databusConfig, Cluster cluster)
      throws Exception {
    super(DataPurgerService.class.getName(), databusConfig, 60000 * 60);
    this.cluster = cluster;
    fs = FileSystem.get(cluster.getHadoopConf());
    this.defaulttrashPathRetentioninHours = new Integer(
        Integer.parseInt(databusConfig
        .getDefaults().get(DatabusConfigParser.TRASH_RETENTION_IN_HOURS)));
    this.defaultstreamPathRetentioninHours = new Integer(
        Integer.parseInt(databusConfig.getDefaults().get(
            DatabusConfigParser.RETENTION_IN_HOURS)));
  }

  @Override
  public void stop() {
    stopped = true;
    /*
     * Pruger can sleep for an hour so it needs to be interuppted
     */
    thread.interrupt();
    LOG.info(Thread.currentThread().getName() + " stopped [" + stopped + "]");
  }

  @Override
  public long getMSecondsTillNextRun(long currentTime) {
    return runIntervalInMsec;
  }

  private void addMergedStreams() {
    Set<String> destinationStreams = cluster.getDestinationStreams();
    for (String destStreamName : destinationStreams) {
      Integer mergedStreamRetentionInHours = -1;

      for (StreamCluster destClusters : getConfig().getAllStreams()
          .get(destStreamName).getDestinationStreamClusters()) {
        DestinationStreamCluster destCluster = (DestinationStreamCluster) destClusters;
        if(destCluster.getCluster().getName().compareTo(cluster.getName())==0)
          mergedStreamRetentionInHours = destCluster.getRetentionPeriod();

      }
      LOG.debug("Merged Stream :: streamName [" + destStreamName
          + "] mergedStreamRetentionInHours [" + mergedStreamRetentionInHours
          + "]");
      if (streamRetention.get(destStreamName) == null) {
        streamRetention.put(destStreamName, mergedStreamRetentionInHours);
        LOG.debug("Adding Merged Stream [" + destStreamName
            + "] retentionInHours [" + mergedStreamRetentionInHours + "]");
      } else {
        // Partial & Merged stream are produced at this cluster
        // choose max retention period
        Integer partialStreamRetentionInHours = streamRetention
            .get(destStreamName);
        if (partialStreamRetentionInHours
            .compareTo(mergedStreamRetentionInHours) > 0) {
          streamRetention.put(destStreamName, partialStreamRetentionInHours);
          LOG.debug("Overriding Stream [" + destStreamName
              + "] retentionInHours ["
              + partialStreamRetentionInHours + "]");

        } else {
          streamRetention.put(destStreamName, mergedStreamRetentionInHours);
          LOG.debug("Overriding Stream [" + destStreamName
              + "] retentionInHours ["
              + mergedStreamRetentionInHours + "]");

        }

      }
    }
  }

  private void addLocalStreams() {
    for (Stream s : getConfig().getAllStreams().values()) {
      for (StreamCluster sourceCluster : s.getSourceStreamClusters()) {
        if (sourceCluster.getCluster().getName().compareTo(cluster.getName()) == 0) {
          String streamName = s.getName();
          Integer retentionInHours = new Integer(
              sourceCluster.getRetentionPeriod());
          streamRetention.put(streamName, retentionInHours);
          LOG.debug("Adding Partial Stream [" + streamName
              + "] with retentionPeriod [" + retentionInHours + " Hours]");
        }
      }
    }
  }

  Integer getTrashPathRetentionInHours() {
    return defaulttrashPathRetentioninHours;
  }

  Integer getRetentionPeriod(String streamName) {
    Integer retentionInHours = streamRetention.get(streamName);
    if (retentionInHours == null)
      retentionInHours = defaultstreamPathRetentioninHours;
    return retentionInHours;
  }

  @Override
  protected void execute() throws Exception {
    try {
      streamRetention = new HashMap<String, Integer>();
      streamsToPurge = new HashSet<Path>();

      // populates - streamRetention
      // Map of streams and their retention period at this cluster (Partial +
      // Merged)
      // Partial streams produced at this cluster - retention period config
      addLocalStreams();
      // Merged streams at this cluster - retention period config
      addMergedStreams();
      String mergedStreamRoot = cluster.getFinalDestDirRoot();
      Map<String, Path> mergedStreamsInClusterPathMap = getStreamsInCluster(mergedStreamRoot);
      String localStreamRoot = cluster.getLocalFinalDestDirRoot();
      Map<String, Path> localStreamsInClusterPathMap = getStreamsInCluster(localStreamRoot);
      getPathsToPurge(mergedStreamsInClusterPathMap,
          localStreamsInClusterPathMap);
      purge();
    } catch (Exception e) {
      LOG.warn(e);
      e.printStackTrace();
      throw new Exception(e);
    }
  }

  private void getPathsToPurge(Map<String, Path> mergedStreamsInClusterPathMap,
      Map<String, Path> localStreamsInClusterPathMap) throws Exception {
    getStreamsPathToPurge(mergedStreamsInClusterPathMap);
    getStreamsPathToPurge(localStreamsInClusterPathMap);
    getTrashPathsToPurge();

  }

  private void getTrashPathsToPurge() throws Exception {
    Path trashRoot = cluster.getTrashPath();
    LOG.debug("Looking for trashPaths in [" + trashRoot + "]");
    FileStatus[] trashDatePaths = getAllFilesInDir(trashRoot, fs);
    // For each trashpath
    if (trashDatePaths != null && trashDatePaths.length >= 1) {
      for (FileStatus trashPath : trashDatePaths) {
        FileStatus[] trashHourPaths = getAllFilesInDir(trashPath.getPath(), fs);
        if (trashHourPaths != null && trashHourPaths.length >= 1) {
          for (FileStatus trashHourPath : trashHourPaths) {
            try {
              Calendar trashPathDate = getDateFromTrashPath(trashPath.getPath()
                  .getName(), trashHourPath.getPath().getName());
              if (isPurge(trashPathDate, getTrashPathRetentionInHours()))
                streamsToPurge.add(trashHourPath.getPath().makeQualified(fs));
            } catch (NumberFormatException e) {
              streamsToPurge.add(trashHourPath.getPath().makeQualified(fs));
            }
          }
        }

      }
    }

  }

  private Calendar getDateFromTrashPath(String trashDatePath,
      String trashHourPath) {
    // Eg: TrashPath :: 2012-1-9
    String[] date = trashDatePath.split("-");
    String year = date[0];
    String month = date[1];
    String day = date[2];
    return CalendarHelper.getDateHour(year, month, day, trashHourPath);

  }

  private Map<String, Path> getStreamsInCluster(String root) throws Exception {
    Map<String, Path> streams = new HashMap<String, Path>();
    LOG.debug("Find streams in [" + root + "]");
    FileStatus[] paths = getAllFilesInDir(new Path(root), fs);
    if (paths != null) {
      for (FileStatus fileStatus : paths) {
        streams.put(fileStatus.getPath().getName(), fileStatus.getPath()
            .makeQualified(fs));
        LOG.debug("Purger working for stream [" + fileStatus.getPath() + "]");
      }
    } else
      LOG.debug("No streams found in [" + root + "]");
    return streams;
  }

  private void getStreamsPathToPurge(Map<String, Path> streamPathMap)
      throws Exception {
    Set<Map.Entry<String, Path>> streamsToProcess = streamPathMap.entrySet();
    for (Map.Entry<String, Path> entry : streamsToProcess) {
      String streamName = entry.getKey();
      Path streamRootPath = entry.getValue();
      LOG.debug("Find Paths to purge for stream [" + streamName
          + "] streamRootPath [" + streamRootPath + "]");
      // For each Stream, all years
      FileStatus[] years = getAllFilesInDir(streamRootPath, fs);
      if (years != null) {
        for (FileStatus year : years) {
          // For each month
          FileStatus[] months = getAllFilesInDir(year.getPath(), fs);
          if (months != null && months.length >= 1) {
            for (FileStatus month : months) {
              // For each day
              FileStatus[] days = getAllFilesInDir(month.getPath(), fs);
              if (days != null && days.length >= 1) {
                for (FileStatus day : days) {
                  // For each day
                  FileStatus[] hours = getAllFilesInDir(day.getPath(), fs);
                  if (hours != null && hours.length >= 1) {
                    for (FileStatus hour : hours) {
                      LOG.debug("Working for hour [" + hour.getPath() + "]");
                      Calendar streamDate = CalendarHelper.getDateHour(year
                          .getPath().getName(), month.getPath().getName(), day
                          .getPath().getName(), hour.getPath().getName());
                      LOG.debug("Validate [" + streamDate.toString()
                          + "] against retentionHours ["
                          + getRetentionPeriod(streamName) + "]");
                      if (isPurge(streamDate, getRetentionPeriod(streamName))) {
                        LOG.debug("Adding stream to purge [" + hour.getPath()
                            + "]");
                        streamsToPurge.add(hour.getPath().makeQualified(fs));
                      }
                    }
                  } else {
                    // No hour found in day. Purge day
                    streamsToPurge.add(day.getPath().makeQualified(fs));
                  }
                } // each day
              } else {
                // No day found in month. Purge month
                streamsToPurge.add(month.getPath().makeQualified(fs));
              }
            }// each month
          } else {
            // no months found in year. Purge Year.
            streamsToPurge.add(year.getPath().makeQualified(fs));
          }
        }// each year
      }
    }// each stream
  }

  public boolean isPurge(Calendar streamDate, Integer retentionPeriodinHours) {
    // int streamDay = streamDate.get(Calendar.DAY_OF_MONTH);
    Calendar nowTime = CalendarHelper.getNowTime();
    String streamDateStr = dateFormat.format(new Date(streamDate
        .getTimeInMillis()));
    String nowTimeStr = dateFormat.format(new Date(nowTime.getTimeInMillis()));

    LOG.debug("streamDate [" + streamDateStr + "] currentDate : [" + nowTimeStr
        + "] against retention [" + retentionPeriodinHours + " Hours]");

    LOG.debug("Hours between streamDate and nowTime is ["
        + getHoursBetweenDates(streamDate, nowTime) + " Hours]");
    if (getHoursBetweenDates(streamDate, nowTime) < retentionPeriodinHours)
      return false;
    else
      return true;
  }

  private int getHoursBetweenDates(Calendar startDate, Calendar endDate) {
    long diff = endDate.getTimeInMillis() - startDate.getTimeInMillis();
    int hours = (int) Math.floor(diff / MILLISECONDS_PER_HOUR);
    return Math.abs(hours);
  }

  private void purge() throws Exception {
    for (Path purgePath : streamsToPurge) {
      fs.delete(purgePath, true);
      LOG.info("Purging [" + purgePath + "]");
    }
  }

  private FileStatus[] getAllFilesInDir(Path dir, FileSystem fs)
      throws Exception {
    return fs.listStatus(dir);
  }
}
