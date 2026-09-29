USE [TradeManagement]
GO
        CREATE TABLE [dbo].[BitcoinPayments] (
                [Id] nvarchar(36) NOT NULL,
                [AccountId] INT NOT NULL,
                [Amount] DECIMAL(18, 2) NOT NULL,
                [Currency] VARCHAR(3) NOT NULL,
                [WalletAddress] nvarchar(64) NOT NULL,
                CONSTRAINT [PK_BitcoinPayments] PRIMARY KEY ([Id]),
                CONSTRAINT [FK_BitcoinPayments_Accounts] FOREIGN KEY ([AccountId]) REFERENCES Accounts([Id])
        )
GO
