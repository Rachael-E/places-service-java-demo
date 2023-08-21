/*
 * Copyright 2023 Esri.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.esri.demo.places_service_demo;

import com.esri.arcgisruntime.ArcGISRuntimeEnvironment;
import com.esri.arcgisruntime.mapping.ArcGISMap;
import com.esri.arcgisruntime.mapping.view.MapView;
import com.esri.arcgisruntime.arcgisservices.LabelDefinition;
import com.esri.arcgisruntime.concurrent.ListenableFuture;
import com.esri.arcgisruntime.geometry.Point;
import com.esri.arcgisruntime.geometry.SpatialReferences;
import com.esri.arcgisruntime.loadable.LoadStatus;
import com.esri.arcgisruntime.mapping.labeling.SimpleLabelExpression;
import com.esri.arcgisruntime.mapping.view.Graphic;
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay;
import com.esri.arcgisruntime.symbology.SimpleRenderer;
import com.esri.arcgisruntime.symbology.Symbol;
import com.esri.arcgisruntime.symbology.SymbolStyle;
import com.esri.arcgisruntime.symbology.TextSymbol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PlacesServiceDemo extends Application {

  private MapView mapView;
  private Symbol symbol;
  HttpClient httpClient = HttpClient.newHttpClient();
  String yourAPIKey = System.getProperty("apiKey");

  @Override
  public void start(Stage stage) {

    try {
      // create stack pane and application scene
      StackPane stackPane = new StackPane();
      Scene scene = new Scene(stackPane);

      // set title, size, and add scene to stage
      stage.setTitle("Places Service Demo");
      stage.setWidth(800);
      stage.setHeight(700);
      stage.setScene(scene);
      stage.show();

      // authentication with an API key or named user is required to access basemaps and other location services
      ArcGISRuntimeEnvironment.setApiKey(yourAPIKey);

      // create a map view and set the map to it
      mapView = new MapView();

      // set up label expression to display the "Name" attribute on the map
      var simpleLabelExpression = new SimpleLabelExpression("[Name]");
      var textSymbol = new TextSymbol(12, "SearchResult", Color.DARKGREEN, TextSymbol.HorizontalAlignment.LEFT, TextSymbol.VerticalAlignment.TOP);
      var labelDefinition = new LabelDefinition(simpleLabelExpression, textSymbol);

      // set up the graphics overlay to display returned places on the map
      var graphicsOverlay = new GraphicsOverlay();
      graphicsOverlay.getLabelDefinitions().add(labelDefinition);
      graphicsOverlay.setLabelsEnabled(true);
      mapView.getGraphicsOverlays().add(graphicsOverlay);

      setBasemapServicesMapToMapView();

      // create a new symbol style from the Esri2DPointSymbolsStyle library (https://developers.arcgis.com/javascript/latest/visualization/symbols-color-ramps/esri-web-style-symbols-2d/)
      var symbolStyle = new SymbolStyle("Esri2DPointSymbolsStyle", null);

      // display an error if the symbol style fails to load
      symbolStyle.addDoneLoadingListener(() -> {
        if (symbolStyle.getLoadStatus() == LoadStatus.FAILED_TO_LOAD) {
          new Alert(Alert.AlertType.ERROR, "Error: could not load symbol style. Details: \n"
            + symbolStyle.getLoadError().getMessage()).show();
        }
      });

      // wait for the symbol ("park" style) to be returned before applying it to returned places
      ListenableFuture<Symbol> searchResult = symbolStyle.getSymbolAsync(Collections.singletonList("park"));
      searchResult.addDoneListener(() -> {
        try {
          // get the symbol and set it in a simple renderer
          symbol = searchResult.get();
          if (symbol != null) {
            var simpleRenderer = new SimpleRenderer(symbol);
            graphicsOverlay.setRenderer(simpleRenderer);
          } else {
            new Alert(Alert.AlertType.ERROR, "Symbol not found").show();
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      });

      // set up URI for Places Service
      String scheme = "https";
      String authority = "places-api.arcgis.com";
      String path = "/arcgis/rest/services/places-service/v1/places/near-point";
      String query =
        "searchText=garden" +
          "&x=-3.19551" +
          "&y=55.94417" +
          "&radius=1000" +
          "&f=pjson" +
          "&token=" + yourAPIKey;
      URI placesServiceUri = new URI(scheme, authority, path, query, null);
      // build the http request for the places service and store the response
      HttpRequest placesServiceHttpRequest = HttpRequest.newBuilder(placesServiceUri).uri(placesServiceUri).GET().build();
      CompletableFuture<HttpResponse<String>> placesServiceCompletableFuture = httpClient.sendAsync(placesServiceHttpRequest, HttpResponse.BodyHandlers.ofString());

      // deserialize JSON to Java object, store Places service http response results in PlaceResult class
      Gson gson = new GsonBuilder().create();
      // get the JSON response and create place results with it
      try {
        placesServiceCompletableFuture
          .thenApply(HttpResponse::body)
          .thenAccept(body -> {
            // if there is an error returned from the http response body throw an exception with info
            if (body.contains("error")) {
              Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, getServerResponseStatusAndDetails(body)).show());
              var exception = new RuntimeException(getServerResponseStatusAndDetails(body));
              exception.printStackTrace();
              throw exception;
            } else { // store results in PlaceResult and display on the map as graphics
              var placeResult = gson.fromJson(body, PlaceResult.class);
              if (!placeResult.results.isEmpty()) {
                placeResult.results.stream()
                  .map(result -> {
                    var graphic = new Graphic(new Point(result.location.x, result.location.y, SpatialReferences.getWgs84()));
                    graphic.getAttributes().put("Name", result.name);
                    return graphic;
                  })
                  .forEach(graphicsOverlay.getGraphics()::add);
                if (!graphicsOverlay.getGraphics().isEmpty()) {
                  mapView.setViewpointCenterAsync(new Point(-3.19551, 55.94417, SpatialReferences.getWgs84()), 20000);
                }
              } else {
                new Alert(Alert.AlertType.ERROR, "No place results returned").show();
              }
            }
          });

      } catch (Exception e) {
        e.printStackTrace();
      }

      // add the map view to the stack pane
      stackPane.getChildren().addAll(mapView);
    } catch (Exception e) {
      // on any error, display the stack trace.
      e.printStackTrace();
    }
  }

  /**
   * Stops and releases all resources used in application.
   */
  @Override
  public void stop() {

    if (mapView != null) {
      mapView.dispose();
    }
  }

  /**
   * Opens and runs application.
   *
   * @param args arguments passed to this application
   */
  public static void main(String[] args) {

    Application.launch(args);
  }

  /**
   * Gets a basemap from the Basemap Styles Services and sets it to the map view.
   * @throws Exception
   */
  private void setBasemapServicesMapToMapView() throws Exception {

    // set up the URI for Basemap Styles Services - outdoor style
    URI basemapUri = new URI("https",
      "basemapstyles-api.arcgis.com",
      "/arcgis/rest/services/styles/v2/webmaps/arcgis/outdoor", null, null);

    // build the http request asynchronously for the Basemap Styles service and store the response
    HttpRequest basemapRequest = HttpRequest.newBuilder(basemapUri)
      .setHeader("Authorization", "Bearer " + yourAPIKey).GET().build();
    HttpResponse<String> basemapHttpResponse = httpClient.send(basemapRequest, HttpResponse.BodyHandlers.ofString());
    var basemapResponseBody = basemapHttpResponse.body();

    // get the JSON response and create an ArcGISMap with it
    if (basemapHttpResponse.statusCode() == 200) {
      mapView.setMap(ArcGISMap.fromJson(basemapResponseBody)); // 200 if successful
    } else {
      new Alert(Alert.AlertType.ERROR, getServerResponseStatusAndDetails(basemapResponseBody)).show();
    }
  }

  /**
   * Gets the server response error and code details
   * @param responseBody the response body
   * @return error details
   */
  private String getServerResponseStatusAndDetails(String responseBody) {
    var errorJsonObject = JsonParser.parseString(responseBody).getAsJsonObject().get("error").getAsJsonObject();
    var statusCode = errorJsonObject.get("code");
    var details = errorJsonObject.get("details").getAsString().stripLeading().stripTrailing();

    return "Server Response: " + statusCode + "\n" + details;
  }

  /**
   * The following classes are for storing results from the Places Service.
   */
  public static class PlaceResult {

    public List<Place> results;

  }

  public static class Pagination {
    public URI previousUrl;
    public URI nextUrl;
  }

  public static class Place {
    public String placeId;
    public PlaceLocation location;
    public List<Category> categories;
    public String name;
  }

  public static class PlaceLocation {
    public float x;
    public float y;
  }

  public static class Category {
    public int categoryId;
    public String label;
  }
}
