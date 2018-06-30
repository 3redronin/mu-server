/**
 *  Copyright 2016 SmartBear Software
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.example.petstore.resource;

import io.muserver.rest.Required;
import org.example.petstore.data.PetData;
import org.example.petstore.data.StoreData;
import org.example.petstore.model.Order;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/store")
//@Api(value="/store" , description = "Operations about store")
@Produces({"application/json", "application/xml"})
public class PetStoreResource {
  static StoreData storeData = new StoreData();
  static PetData petData = new PetData();

  @GET
  @Path("/inventory")
  @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
//  @ApiOperation(value = "Returns pet inventories by status",
//    notes = "Returns a map of status codes to quantities",
//    response = Integer.class,
//    responseContainer = "map",
//    authorizations = @Authorization(value = "api_key")
//  )
  public java.util.Map<String, Integer> getInventory() {
    return petData.getInventoryByStatus();
  }

  @GET
  @Path("/order/{orderId}")
//  @ApiOperation(value = "Find purchase order by ID",
//    notes = "For valid response try integer IDs with value >= 1 and <= 10. Other values will generated exceptions",
//    response = Order.class)
//  @ApiResponses(value = { @ApiResponse(code = 400, message = "Invalid ID supplied"),
//      @ApiResponse(code = 404, message = "Order not found") })
  public Response getOrderById(
//      @ApiParam(value = "ID of pet that needs to be fetched", allowableValues = "range[1,10]", )
      @Required
      @PathParam("orderId") Long orderId)
      throws NotFoundException {
    Order order = storeData.findOrderById(orderId);
    if (null != order) {
      return Response.ok().entity(order).build();
    } else {
      throw new NotFoundException("Order not found");
    }
  }

  @POST
  @Path("/order")
//  @ApiOperation(value = "Place an order for a pet")
//  @ApiResponses({ @ApiResponse(code = 400, message = "Invalid Order") })
  public Order placeOrder(
//      @ApiParam(value = "order placed for purchasing the pet", )
      @Required
		      Order order) {
    storeData.placeOrder(order);
    return storeData.placeOrder(order);
  }

  @DELETE
  @Path("/order/{orderId}")
//  @ApiOperation(value = "Delete purchase order by ID",
//    notes = "For valid response try integer IDs with positive integer value. Negative or non-integer values will generate API errors")
//  @ApiResponses(value = { @ApiResponse(code = 400, message = "Invalid ID supplied"),
//      @ApiResponse(code = 404, message = "Order not found") })
  public Response deleteOrder(
//      @ApiParam(value = "ID of the order that needs to be deleted", allowableValues = "range[1,infinity]", )
      @Required
      @PathParam("orderId") Long orderId) {
    if (storeData.deleteOrder(orderId)) {
      return Response.ok().entity("").build();
    } else {
      return Response.status(Response.Status.NOT_FOUND).entity("Order not found").build();
    }
  }
}
