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

            System.out.println(input.readLine());
            System.out.println("Available commands:");
            System.out.println("LIST");
            System.out.println("RESERVE|DOG|PetName");
            System.out.println("RESERVE|CAT|PetName");
            System.out.println("GET|ReservationID");
            System.out.println("CANCEL|ReservationID");
            System.out.println("QUIT");

            while (true) {

                System.out.print("Enter command: ");

                if (!scanner.hasNextLine()) {
                    break;
                }

                String command = scanner.nextLine();

                output.println(command);

                String response = input.readLine();

                if (response == null) {
                    System.out.println("Server disconnected.");
                    break;
                }

                System.out.println("Server: " + response);

                if (command.trim().equalsIgnoreCase("QUIT")) {
                    break;
                }
            }

        } catch (IOException e) {
            System.out.println(
                    "Unable to connect. Make sure the server is running.");
        }
    }
}
