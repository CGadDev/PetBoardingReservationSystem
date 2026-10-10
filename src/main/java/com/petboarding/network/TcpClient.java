package com.petboarding.network;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class TcpClient {

    public static void main(String[] args) {

        try (Socket socket = new Socket(
                    InetAddress.getLoopbackAddress(), 5050);

             BufferedReader input = new BufferedReader(
                     new InputStreamReader(
                             socket.getInputStream(),
                             StandardCharsets.UTF_8));

             PrintWriter output = new PrintWriter(
                     new OutputStreamWriter(
                             socket.getOutputStream(),
                             StandardCharsets.UTF_8), true);

             Scanner scanner = new Scanner(System.in)) {

            socket.setSoTimeout(300000);

            // Display the server's welcome message.
            System.out.println(input.readLine());

            System.out.println("Available commands:");
            System.out.println("LIST");
            System.out.println("AUTH|YourApiKey");
            System.out.println("RESERVE|DOG|PetName");
            System.out.println("RESERVE|CAT|PetName");
            System.out.println("GET|ReservationID");
            System.out.println("CANCEL|ReservationID");
            System.out.println("QUIT");

            System.out.println("------------------------------");
            System.out.println(
                    "Authenticate before modifying reservations.");
            System.out.println(
                    "LIST can be used without authentication.");

            // Read commands from the user.
            while (true) {

                System.out.print("Enter command: ");

                if (!scanner.hasNextLine()) {
                    break;
                }

                String command = scanner.nextLine();

                // Send the command to the TCP server.
                output.println(command);

                // Receive the server response.
                String response = input.readLine();

                if (response == null) {
                    System.out.println(
                            "Server disconnected.");
                    break;
                }

                System.out.println("Server: " + response);

                if (command.trim().equalsIgnoreCase("QUIT")) {
                    break;
                }
            }

        } catch (SocketTimeoutException e) {

            System.out.println(
                    "Connection timed out.");

        } catch (ConnectException e) {

            System.out.println(
                    "Unable to connect. Make sure the server is running.");

        } catch (IOException e) {

            System.out.println(
                    "Connection error: " + e.getMessage());
        }
    }
}
