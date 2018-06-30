/**
 * Copyright 2016 SmartBear Software
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.example.petstore.resource;


import io.muserver.rest.Description;
import org.example.petstore.data.VehicleData;
import org.example.petstore.model.Vehicle;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.OutputStream;

@Path("/vehicle")
@Description(value = "/vehicle", details = "Operations about vehicles")
//    authorizations = {
//  @Authorization(value = "vehiclestore_auth",
//  scopes = {
//    @AuthorizationScope(scope = "write:vehicle", description = "modify vehicles in your account"),
//    @AuthorizationScope(scope = "read:vehicle", description = "read your vehicles")
//  })
//}, tags = "vehicle")
@Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
public class VehicleResource {
    static VehicleData vehicleData = new VehicleData();

    @GET
    @Path("/{vehicleId}")
//  @ApiOperation(value = "Find vehicle by ID",
//    notes = "Returns a vehicle when ID <= 10.  ID > 10 or nonintegers will simulate API error conditions",
//    response = Vehicle.class,
//    authorizations = @Authorization(value = "api_key")
//  )
//  @ApiResponses(value = { @ApiResponse(code = 400, message = "Invalid ID supplied"),
//      @ApiResponse(code = 404, message = "Vehicle not found") })
    public Response getVehicleById(
//      @ApiParam(value = "ID of vehicle that needs to be fetched", allowableValues = "range[1,10]", required = true)
        @PathParam("vehicleId") Long vehicleId)
        throws NotFoundException {
        Vehicle vehicle = vehicleData.getVehicleById(vehicleId);
        if (vehicle != null) {
            return Response.ok().entity(vehicle).build();
        } else {
            throw new NotFoundException("Vehicle not found");
        }
    }

    @GET
    @Path("/{vehicleId}/download")
//  @ApiOperation(value = "Find vehicle by ID",
//    notes = "Returns a vehicle when ID <= 10.  ID > 10 or nonintegers will simulate API error conditions",
//    response = Vehicle.class,
//    authorizations = @Authorization(value = "api_key")
//  )
//  @ApiResponses(value = { @ApiResponse(code = 400, message = "Invalid ID supplied"),
//      @ApiResponse(code = 404, message = "Vehicle not found") })
    public Response downloadFile(
//      @ApiParam(value = "ID of vehicle that needs to be fetched", allowableValues = "range[1,10]", required = true)
        @PathParam("vehicleId") Long vehicleId)
        throws NotFoundException {
        StreamingOutput stream = new StreamingOutput() {
            @Override
            public void write(OutputStream output) throws IOException {
                try {
                    // TODO: write file content to output;
                    output.write("hello, world".getBytes());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };

        return Response.ok(stream, "application/force-download")
            .header("Content-Disposition", "attachment; filename = foo.bar")
            .build();
    }

    @DELETE
    @Path("/{vehicleId}")
//  @ApiOperation(value = "Deletes a vehicle")
//  @ApiResponses(value = { @ApiResponse(code = 400, message = "Invalid ID supplied"),
//          @ApiResponse(code = 404, message = "Vehicle not found")})
    public Response deleteVehicle(
        @HeaderParam("api_key")
            String apiKey,
//    @ApiParam(value = "", required = true)
        @Description("Vehicle id to delete")
        @PathParam("vehicleId") Long vehicleId) {
        if (vehicleData.deleteVehicle(vehicleId)) {
            return Response.ok().build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @POST
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
//  @ApiOperation(value = "Add a new vehicle to the store")
//  @ApiResponses(value = { @ApiResponse(code = 405, message = "Invalid input", response = io.muserver.examples.model.ApiResponse.class) })
    public Response addVehicle(
//      @ApiParam(value = "Vehicle object that needs to be added to the store", required = true)
        Vehicle vehicle) {
        Vehicle updatedVehicle = vehicleData.addVehicle(vehicle);
        return Response.ok().entity(updatedVehicle).build();
    }

    @PUT
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
//  @ApiOperation(value = "Update an existing vehicle")
//  @ApiResponses(value = { @ApiResponse(code = 400, message = "Invalid ID supplied"),
//      @ApiResponse(code = 404, message = "Vehicle not found"),
//      @ApiResponse(code = 405, message = "Validation exception") })
    public Response updateVehicle(
//      @ApiParam(value = "Vehicle object that needs to be added to the store", required = true)
        Vehicle vehicle) {
        Vehicle updatedVehicle = vehicleData.addVehicle(vehicle);
        return Response.ok().entity(updatedVehicle).build();
    }

    @POST
    @Path("/{vehicleId}")
    @Consumes({MediaType.APPLICATION_FORM_URLENCODED})
//  @ApiOperation(value = "Updates a vehicle in the store with form data",
//    consumes = MediaType.APPLICATION_FORM_URLENCODED)
//  @ApiResponses(value = {
//    @ApiResponse(code = 405, message = "Invalid input")})
    public Response updateVehicleWithForm(
        @Description(value = "ID of vehicle that needs to be updated")
        @PathParam("vehicleId")
            Long vehicleId,
//   @ApiParam(value = "Updated name of the vehicle", required = false)
        @FormParam("name")
            String name,
//   @ApiParam(value = "Updated status of the vehicle", required = false)
        @FormParam("status")
            String status) {
        Vehicle vehicle = vehicleData.getVehicleById(vehicleId);
        if (vehicle != null) {
            if (name != null && !"".equals(name))
                vehicle.setName(name);
            vehicleData.addVehicle(vehicle);
            return Response.ok().build();
        } else
            return Response.status(404).build();
    }
}
