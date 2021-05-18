package org.heigit.ohsome.ohsomeapi.executor;

import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDBIgnite;
import org.heigit.bigspatialdata.oshdb.api.db.OSHDBIgnite.ComputeMode;
import org.heigit.bigspatialdata.oshdb.api.mapreducer.MapReducer;
import org.heigit.bigspatialdata.oshdb.api.object.OSMContribution;
import org.heigit.bigspatialdata.oshdb.util.OSHDBTimestamp;
import org.heigit.ohsome.filter.FilterExpression;
import org.heigit.ohsome.ohsomeapi.Application;
import org.heigit.ohsome.ohsomeapi.inputprocessing.InputProcessor;
import org.heigit.ohsome.ohsomeapi.inputprocessing.ProcessingData;
import org.heigit.ohsome.ohsomeapi.oshdb.DbConnData;
import org.heigit.ohsome.ohsomeapi.output.Attribution;
import org.heigit.ohsome.ohsomeapi.output.DefaultAggregationResponse;
import org.heigit.ohsome.ohsomeapi.output.Description;
import org.heigit.ohsome.ohsomeapi.output.Metadata;
import org.heigit.ohsome.ohsomeapi.output.Response;
import org.heigit.ohsome.ohsomeapi.output.contributions.ContributionsResult;
import org.locationtech.jts.geom.Geometry;

/**
 * Includes the execute method for requests mapped to /contributions/count,
 * /contributions/latest/count and /users/count.
 */
public class ContributionsExecutor extends RequestExecutor {

  private final InputProcessor inputProcessor;
  private final ProcessingData processingData;
  private final long startTime = System.currentTimeMillis();

  public ContributionsExecutor(HttpServletRequest servletRequest,
      HttpServletResponse servletResponse, boolean isDensity) {
    super(servletRequest, servletResponse);
    inputProcessor = new InputProcessor(servletRequest, false, isDensity);
    processingData = inputProcessor.getProcessingData();
  }

  /**
   * Performs a count calculation using contributions for the endpoints /contributions/count,
   * /contribution/latest/count or /users/count.
   *
   * @return {@link org.heigit.ohsome.ohsomeapi.output.Response Response}
   * @throws Exception thrown by
   *         {@link org.heigit.ohsome.ohsomeapi.inputprocessing.InputProcessor#processParameters()
   *         processParameters},
   *         {@link org.heigit.bigspatialdata.oshdb.api.mapreducer.MapAggregator#count() count}
   */
  public Response count(boolean isUsersRequest, boolean isContributionsLatestCount)
      throws Exception {
    MapReducer<OSMContribution> mapRed;
    final SortedMap<OSHDBTimestamp, ? extends Number> result;
    inputProcessor.getProcessingData().setFullHistory(true);
    if (DbConnData.db instanceof OSHDBIgnite) {
      // on ignite: Use AffinityCall backend, which is the only one properly supporting streaming
      // of result data, without buffering the whole result in memory before returning the result.
      // This allows to write data out to the client via a chunked HTTP response.
      mapRed = inputProcessor.processParameters(ComputeMode.AffinityCall);
    } else {
      mapRed = inputProcessor.processParameters();
    }
    if (isUsersRequest) {
      result = mapRed.aggregateByTimestamp().map(OSMContribution::getContributorUserId).countUniq();
    } else if (isContributionsLatestCount) {
      MapReducer<List<OSMContribution>> mapRedGroupByEntity = mapRed.groupByEntity();
      Optional<FilterExpression> filter = processingData.getFilterExpression();
      if (filter.isPresent()) {
        mapRedGroupByEntity = mapRedGroupByEntity.filter(filter.get());
      }
      result = mapRedGroupByEntity
          .map(listContrsPerEntity -> listContrsPerEntity.get(listContrsPerEntity.size() - 1))
          .aggregateByTimestamp(OSMContribution::getTimestamp).count();
    } else {
      result = mapRed.aggregateByTimestamp().count();
    }
    Geometry geom = inputProcessor.getGeometry();
    RequestParameters requestParameters = processingData.getRequestParameters();
    ContributionsResult[] results = ExecutionUtils.fillContributionsResult(result,
        requestParameters.isDensity(), inputProcessor, df, geom);
    Metadata metadata = null;
    if (processingData.isShowMetadata()) {
      long duration = System.currentTimeMillis() - startTime;
      String description;
      if (isUsersRequest) {
        description = Description.countUsers(requestParameters.isDensity());
      } else {
        description = Description.countContributions(requestParameters.isDensity());
      }
      metadata = new Metadata(duration, description,
          inputProcessor.getRequestUrlIfGetRequest(servletRequest));
    }
    if ("csv".equalsIgnoreCase(requestParameters.getFormat())) {
      ExecutionUtils exeUtils = new ExecutionUtils(processingData);
      exeUtils.writeCsvResponse(results, servletResponse,
          ExecutionUtils.createCsvTopComments(URL, TEXT, Application.API_VERSION, metadata));
      return null;
    }
    return DefaultAggregationResponse.of(new Attribution(URL, TEXT), Application.API_VERSION,
        metadata, results);
  }
}
