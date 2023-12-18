package io.quarkiverse.solace;

import static org.awaitility.Awaitility.await;

import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.common.mapper.TypeRef;

@QuarkusTest
class SolaceTest {

    private final TypeRef<List<String>> listOfString = new TypeRef<>() {
        // Empty
    };

    @Test
    void testDirect() {
        List<String> list = RestAssured
                .given().header("Accept", "application/json")
                .get("/solace/direct").as(listOfString);
        Assertions.assertThat(list).isEmpty();

        for (int i = 0; i < 3; i++) {
            RestAssured
                    .given().body("hello " + i)
                    .post("/solace/direct")
                    .then().statusCode(204);
        }

        await().until(() -> RestAssured
                .given().header("Accept", "application/json")
                .get("/solace/direct").as(listOfString).size() == 3);
    }

    @Test
    void testPersistent() {
        List<String> list = RestAssured
                .given().header("Accept", "application/json")
                .get("/solace/persistent").as(listOfString);
        Assertions.assertThat(list).isEmpty();

        for (int i = 0; i < 3; i++) {
            RestAssured
                    .given().body("hello " + i)
                    .post("/solace/persistent")
                    .then().statusCode(204);
        }

        await().until(() -> RestAssured
                .given().header("Accept", "application/json")
                .get("/solace/persistent").as(listOfString).size() == 3);
    }

}
