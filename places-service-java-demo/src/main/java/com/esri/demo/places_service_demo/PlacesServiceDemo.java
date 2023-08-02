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
import com.esri.arcgisruntime.mapping.Viewpoint;
import com.esri.arcgisruntime.mapping.labeling.SimpleLabelExpression;
import com.esri.arcgisruntime.mapping.view.Graphic;
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay;
import com.esri.arcgisruntime.symbology.SimpleRenderer;
import com.esri.arcgisruntime.symbology.Symbol;
import com.esri.arcgisruntime.symbology.SymbolStyle;
import com.esri.arcgisruntime.symbology.TextSymbol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javafx.application.Application;
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

public class PlacesServiceDemo extends Application {

  private MapView mapView;
  private Symbol symbol;
  HttpClient httpClient = HttpClient.newHttpClient();

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
      String yourAPIKey = System.getProperty("apiKey");
      ArcGISRuntimeEnvironment.setApiKey(yourAPIKey);

      // create a map view and set the map to it
      mapView = new MapView();

      // set up label expression to display the "Name" attribute on the map
      var simpleLabelExpression = new SimpleLabelExpression("[Name]");
      var textSymbol = new TextSymbol(10, "SearchResult", Color.DARKGREEN, TextSymbol.HorizontalAlignment.LEFT, TextSymbol.VerticalAlignment.TOP);
      var labelDefinition = new LabelDefinition(simpleLabelExpression, textSymbol);

      // set up the graphics overlay to display returned places on the map
      var graphicsOverlay = new GraphicsOverlay();
      graphicsOverlay.getLabelDefinitions().add(labelDefinition);
      graphicsOverlay.setLabelsEnabled(true);
      mapView.getGraphicsOverlays().add(graphicsOverlay);

      // set up URI for Places Service
      String domainName = "https://places-api.arcgis.com/";
      String path = "arcgis/rest/services/places-service/v1/";
      String requestType = "places/near-point?";
      URI placesServiceUri = new URI(domainName + path + requestType + "searchText=garden&x=-3.19551&y=55.94417&radius=1000&f=pjson&token=" + yourAPIKey);
      // build the http request for the places service and store the response
      HttpRequest placesServiceHttpRequest = HttpRequest.newBuilder().uri(placesServiceUri).GET().build();
      HttpResponse<String> placesServiceHttpResponse = httpClient.send(placesServiceHttpRequest, HttpResponse.BodyHandlers.ofString());

      // set up the URI for Basemap Styles Services - outdoor style
      URI basemapUri = new URI("https://basemapstyles-api.arcgis.com/arcgis/rest/services/styles/v2/webmaps/arcgis/outdoor");
      // build the http request for the Basemap Styles service and store the response
      HttpRequest basemapRequest = HttpRequest.newBuilder().uri(basemapUri).setHeader("Authorization", "Bearer " + yourAPIKey).GET().build();
      HttpResponse<String> basemapResponse = httpClient.send(basemapRequest, HttpResponse.BodyHandlers.ofString());
      // set the map to the JSON response from the basemap styles service
      mapView.setMap(ArcGISMap.fromJson(basemapResponse.body()));

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
            SimpleRenderer simpleRenderer = new SimpleRenderer(symbol);
            graphicsOverlay.setRenderer(simpleRenderer);
          } else {
            new Alert(Alert.AlertType.ERROR, "Symbol not foud").show();
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      });

      // deserialize JSON to Java object, store Places service http response results in PlaceResult class
      Gson gson = new GsonBuilder().create();
      PlaceResult placeResult = gson.fromJson(placesServiceHttpResponse.body(), PlaceResult.class);

      if (!placeResult.results.isEmpty()) {
        for (Place result : placeResult.results) {
          // get the place results co-ordinates
          double x = result.location.x;
          double y = result.location.y;

          // create a new point with the co-ordinates and display them as a graphic on the graphics overlay
          Point resultPoint = new Point(x, y, SpatialReferences.getWgs84());
          Graphic resultGraphic = new Graphic(resultPoint);
          resultGraphic.getAttributes().put("Name", result.name);
          graphicsOverlay.getGraphics().add(resultGraphic);
        }

        // set the viewpoint of the map view to central location
        mapView.setViewpoint(new Viewpoint(new Point(-3.19551, 55.94417, SpatialReferences.getWgs84()), 30000)); // central location

      } else {
        new Alert(Alert.AlertType.ERROR, "No place results returned").show();
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
