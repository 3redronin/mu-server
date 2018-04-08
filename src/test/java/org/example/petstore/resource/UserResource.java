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

import io.muserver.rest.ApiResponse;
import io.muserver.rest.Description;
import org.example.petstore.data.UserData;
import org.example.petstore.exception.ApiException;
import org.example.petstore.model.User;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

@Path("/user")
@Description("Operations about user")
@Produces({"application/json", "application/xml"})
public class UserResource {
    static UserData userData = new UserData();

    @POST
    @Description(value = "Create user", details = "This can only be done by the logged in user.")
//    position = 1)
    public Response createUser(
//      @ApiParam(value = "Created user object", required = true)
        User user) {
        userData.addUser(user);
        return Response.ok().entity("").build();
    }

    @POST
    @Path("/createWithArray")
//  @ApiOperation(value = "Creates list of users with given input array",
//    position = 2)
    public Response createUsersWithArrayInput(
//  		@ApiParam(value = "List of user object", required = true)
        User[] users) {
        for (User user : users) {
            userData.addUser(user);
        }
        return Response.ok().entity("").build();
    }

    @POST
    @Path("/createWithList")
//  @ApiOperation(value = "Creates list of users with given input array",
//    position = 3)
    public Response createUsersWithListInput(
//  		@ApiParam(value = "List of user object", required = true)
        java.util.List<User> users) {
        for (User user : users) {
            userData.addUser(user);
        }
        return Response.ok().entity("").build();
    }

    @PUT
    @Path("/{username}")
    @Description(value = "Update user", details = "This can only be done by the logged in user.")
//    position = 4)
    @ApiResponse(code = "400", message = "Invalid user supplied")
    @ApiResponse(code = "404", message = "User not found")
    public Response updateUser(
        @Description("name that need to be deleted") //, required = true)
        @PathParam("username") String username,
        @Description("Updated user object") //, required = true)
            User user) {
        userData.addUser(user);
        return Response.ok().entity("").build();
    }

    @DELETE
    @Path("/{username}")
    @Description(value = "Delete user", details = "This can only be done by the logged in user.")
//    position = 5)
    @ApiResponse(code = "400", message = "Invalid username supplied")
    @ApiResponse(code = "404", message = "User not found")
    public Response deleteUser(
//      @ApiParam(value = "The name that needs to be deleted", required = true)
        @Description("The name that needs to be deleted")
        @PathParam("username") String username) {
        if (userData.removeUser(username)) {
            return Response.ok().entity("").build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @GET
    @Path("/{username}")
    @Description("Get user by user name")
//    response = User.class,
//    position = 0)
    @ApiResponse(code = "200", message = "Successful operation", response=User.class)
    @ApiResponse(code = "400", message = "Invalid username supplied")
    @ApiResponse(code = "404", message = "User not found")
    public Response getUserByName(
        @Description(value = "The name that needs to be fetched. Use user1 for testing.")
        @PathParam("username") String username)
        throws ApiException {
        User user = userData.findUserByName(username);
        if (null != user) {
            return Response.ok().entity(user).build();
        } else {
            throw new NotFoundException("User not found");
        }
    }

    @GET
    @Path("/login")
//  @ApiOperation(value = "Logs user into the system",
//    response = String.class,
//    position = 6)
//  @ApiResponses(value = { @ApiResponse(code = 400, message = "Invalid username/password supplied") })
    public Response loginUser(
//      @ApiParam(value = "The user name for login", required = true)
        @QueryParam("username") String username,
//      @ApiParam(value = "The password for login in clear text", required = true)
        @QueryParam("password") String password) {
        return Response.ok()
            .entity("logged in user session:" + System.currentTimeMillis())
            .build();
    }

    @GET
    @Path("/logout")
//  @ApiOperation(value = "Logs out current logged in user session",
//    position = 7)
    public Response logoutUser() {
        return Response.ok().entity("").build();
    }
}
